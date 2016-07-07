/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs

import quasar.Predef._
import quasar._, Planner._, RenderTree.ops._
import quasar.effect.LiftedOps
import quasar.fp._
import quasar.qscript._

import matryoshka._, TraverseT.ops._
import pathy.Path._
import scalaz._, Scalaz._
import scalaz.iteratee._
import scalaz.stream.Process

sealed trait QueryFile[A]

object QueryFile {
  final case class ResultHandle(run: Long) extends scala.AnyVal

  object ResultHandle {
    implicit val resultHandleShow: Show[ResultHandle] =
      Show.showFromToString

    implicit val resultHandleOrder: Order[ResultHandle] =
      Order.orderBy(_.run)
  }

  val qscript = new Transform[Fix]
  val optimize = new Optimize[Fix]
  val elide = scala.Predef.implicitly[ElideBuckets.Aux[Fix, QScriptInternal[Fix, ?]]]

  /** This is a stop-gap function that QScript-based backends should use until
    * LogicalPlan no longer needs to be exposed.
    */
  val convertToQScript: Fix[LogicalPlan] => PlannerError \/ Fix[QScriptPure[Fix, ?]] =
    _.transCataM(qscript.lpToQScript).evalZero.map(
      _.transCata(elide.purify ⋙ optimize.applyAll))

  /** The result of the query is stored in an output file
    * instead of being returned to the user immidiately.
    * The `LogicalPlan` is expected to only contain absolute paths even though
    * that is unfortunatly not expressed in the types currently.
    */
  final case class ExecutePlan(lp: Fix[LogicalPlan], out: AFile)
    extends QueryFile[(PhaseResults, FileSystemError \/ AFile)]

  /** The result of the query is immidiately
    * streamed back to the client. This operation begins the streaming, in order
    * to continue the streaming, the client must make use of the `More` operation and
    * finally the `Close` operation in order to halt the streaming.
    * The `LogicalPlan` is expected to only contain absolute paths even though
    * that is unfortunatly not expressed in the types currently.
    */
  final case class EvaluatePlan(lp: Fix[LogicalPlan])
    extends QueryFile[(PhaseResults, FileSystemError \/ ResultHandle)]

  /** Used to continue streaming after initiating a streaming
    * result with the `EvaluatePlan` operation.
    */
  final case class More(h: ResultHandle)
    extends QueryFile[FileSystemError \/ Vector[Data]]

  /** Used to halt streaming of a result set initiated using
    * the `EvaluatePlan` operation.
    */
  final case class Close(h: ResultHandle)
    extends QueryFile[Unit]

  /** Represents an "explain plan" operation. This operation should not actually
    * have any side effect on the filesystem, it should simply return useful
    * information to the user about how a given query would be evaluated on
    * this filesystem implementation.
    * The `LogicalPlan` is expected to only contain absolute paths even though
    * that is unfortunatly not expressed in the types currently.
    */
  final case class Explain(lp: Fix[LogicalPlan])
    extends QueryFile[(PhaseResults, FileSystemError \/ ExecutionPlan)]

  /** This operation lists the names of all the immidiate children of the supplied directory
    * in the filesystem.
    */
    /* TODO: While this is a bit better in one dimension here in `QueryFile`,
    *       `@mossprescott` points out it is still a bit of a stretch to include
    *       in this algebra. We need to revisit this and probably add algebras
    *       over multiple dimensions to better organize these (and other)
    *       operations.
    *
    *       For more discussion, see
    *       https://github.com/quasar-analytics/quasar/pull/986#discussion-diff-45081757
    */
  final case class ListContents(dir: ADir)
    extends QueryFile[FileSystemError \/ Set[PathSegment]]

  /** This operation should return whether a file exists in the filesystem.*/
  final case class FileExists(file: AFile)
    extends QueryFile[Boolean]

  // TODO[scalaz]: Shadow the scalaz.Monad.monadMTMAB SI-2712 workaround
  import EitherT.eitherTMonad

  final class Ops[S[_]](implicit S: QueryFile :<: S)
    extends LiftedOps[QueryFile, S] {

    type M[A] = FileSystemErrT[F, A]

    val unsafe = Unsafe[S]
    val transforms = Transforms[F]
    import transforms._

    /** Returns the path to the result of executing the given [[LogicalPlan]],
      * using the provided path if possible.
      *
      * Execution of certain plans may return a result file other than the
      * requested file if it is more efficient to do so (i.e. to avoid copying
      * lots of data for a plan consisting of a single `ReadF(...)`).
      */
    def execute(plan: Fix[LogicalPlan], out: AFile): ExecM[AFile] =
      EitherT(WriterT(lift(ExecutePlan(plan, out))): G[FileSystemError \/ AFile])

    /** Returns an enumerator of data resulting from evaluating the given
      * [[LogicalPlan]].
      */
    def enumerate(plan: Fix[LogicalPlan]): EnumeratorT[Data, ExecM] = {
      import Iteratee._

      val enumHandle: EnumeratorT[ResultHandle, ExecM] =
        unsafe.eval(plan).liftM[EnumT]

      def enumData(h: ResultHandle): EnumeratorT[Vector[Data], ExecM] =
        new EnumeratorT[Vector[Data], ExecM] {
          def apply[A] = s => s mapContOr (k =>
            iterateeT(hoistToExec(unsafe.more(h)) flatMap { data =>
              if (data.isEmpty)
                toExec(unsafe.close(h)) *> k(emptyInput).value
              else
                (k(elInput(data)) >>== apply[A]).value
            }),
            iterateeT(toExec(unsafe.close(h) as s)))
        }

      enumHandle flatMap enumData flatMap (enumIndexedSeq[Data, ExecM](_))
    }

    /** Returns the stream of data resulting from evaluating the given
      * [[LogicalPlan]].
      */
    def evaluate(plan: Fix[LogicalPlan]): Process[ExecM, Data] = {
      def moreUntilEmpty(h: ResultHandle): Process[M, Data] =
        Process.await(unsafe.more(h): M[Vector[Data]]) { data =>
          if (data.isEmpty)
            Process.halt
          else
            Process.emitAll(data) ++ moreUntilEmpty(h)
        }

      def close(h: ResultHandle): ExecM[Unit] =
        toExec(unsafe.close(h))

      Process.bracket(unsafe.eval(plan))(h => Process.eval_(close(h))) { h =>
        moreUntilEmpty(h).translate(hoistToExec)
      }
    }

    /** Returns a description of how the the given logical plan will be
      * executed.
      */
    def explain(plan: Fix[LogicalPlan]): ExecM[ExecutionPlan] =
      EitherT(WriterT(lift(Explain(plan))): G[FileSystemError \/ ExecutionPlan])

    /** Returns the names of the immediate children of the given directory,
      * fails if the directory does not exist.
      */
    def ls(dir: ADir): M[Set[PathSegment]] =
      EitherT(lift(ListContents(dir)))

    /** The children of the root directory. */
    def ls: M[Set[PathSegment]] =
      ls(rootDir)

    /** Returns all files in this directory and all of it's sub-directories
      * Fails if the directory does not exist.
      */
    def descendantFiles(dir: ADir): M[Set[RFile]] = {
      type S[A] = StreamT[M, A]

      def lsR(desc: RDir): StreamT[M, RFile] =
        StreamT.fromStream[M, PathSegment](ls(dir </> desc) map (_.toStream))
          .flatMap(_.fold(
            d => lsR(desc </> dir1(d)),
            f => (desc </> file1(f)).point[S]))

      lsR(currentDir).foldLeft(Set.empty[RFile])(_ + _)
    }

    /** Returns whether the given file exists. */
    def fileExists(file: AFile): F[Boolean] =
      lift(FileExists(file))

    /** Returns whether the given file exists, lifted into the same monad as
      * the rest of the functions here, for convenience.
      */
    def fileExistsM(file: AFile): M[Boolean] =
      fileExists(file).liftM[FileSystemErrT]

    ////

    private val hoistToExec: M ~> ExecM =
      Hoist[FileSystemErrT].hoist[F, G](liftMT[F, PhaseResultT])
  }

  object Ops {
    implicit def apply[S[_]](implicit S: QueryFile :<: S): Ops[S] =
      new Ops[S]
  }

  /** Low-level, unsafe operations. Clients are responsible for resource-safety
    * when using these.
    */
  final class Unsafe[S[_]](implicit S: QueryFile :<: S)
    extends LiftedOps[QueryFile, S] {

    val transforms = Transforms[F]
    import transforms._

    /** Returns a handle to the results of evaluating the given [[LogicalPlan]]
      * that can be used to read chunks of result data.
      *
      * Care must be taken to `close` the returned handle in order to avoid
      * potential resource leaks.
      */
    def eval(lp: Fix[LogicalPlan]): ExecM[ResultHandle] =
      EitherT(WriterT(lift(EvaluatePlan(lp))): G[FileSystemError \/ ResultHandle])

    /** Read the next chunk of data from the result set represented by the given
      * handle.
      *
      * An empty `Vector` signals that all data has been read.
      */
    def more(rh: ResultHandle): FileSystemErrT[F, Vector[Data]] =
      EitherT(lift(More(rh)))

    /** Closes the given result handle, freeing any resources it was using. */
    def close(rh: ResultHandle): F[Unit] =
      lift(Close(rh))
  }

  object Unsafe {
    implicit def apply[S[_]](implicit S: QueryFile :<: S): Unsafe[S] =
      new Unsafe[S]
  }

  class Transforms[F[_]: Monad] {
    type G[A] = PhaseResultT[F, A]
    type H[A] = SemanticErrsT[G, A]

    type ExecM[A]     = FileSystemErrT[G, A]
    type CompExecM[A] = FileSystemErrT[H, A]

    val execToCompExec: ExecM ~> CompExecM =
      Hoist[FileSystemErrT].hoist[G, H](liftMT[G, SemanticErrsT])

    val compToCompExec: CompileM ~> CompExecM = {
      val hoistW: PhaseResultW ~> G = Hoist[PhaseResultT].hoist(pointNT[F])
      val hoistC: CompileM ~> H     = Hoist[SemanticErrsT].hoist(hoistW)
      liftMT[H, FileSystemErrT] compose hoistC
    }

    val toExec: F ~> ExecM =
      liftMT[G, FileSystemErrT] compose liftMT[F, PhaseResultT]

    def fsErrToExec: FileSystemErrT[F, ?] ~> ExecM =
      Hoist[FileSystemErrT].hoist[F, PhaseResultT[F, ?]](liftMT[F, PhaseResultT])

    val toCompExec: F ~> CompExecM =
      execToCompExec compose toExec
  }

  object Transforms {
    def apply[F[_]: Monad]: Transforms[F] =
      new Transforms[F]
  }

  implicit def renderTree[A]: RenderTree[QueryFile[A]] =
    new RenderTree[QueryFile[A]] {
      def render(qf: QueryFile[A]) = qf match {
        case ExecutePlan(lp, out) => NonTerminal(List("ExecutePlan"), None, List(lp.render, out.render))
        case EvaluatePlan(lp)     => NonTerminal(List("EvaluatePlan"), None, List(lp.render))
        case More(handle)         => Terminal(List("More"), handle.toString.some)
        case Close(handle)        => Terminal(List("Close"), handle.toString.some)
        case Explain(lp)          => NonTerminal(List("Explain"), None, List(lp.render))
        case ListContents(dir)    => NonTerminal(List("ListContents"), None, List(dir.render))
        case FileExists(file)     => NonTerminal(List("FileExists"), None, List(file.render))
      }
    }
}
