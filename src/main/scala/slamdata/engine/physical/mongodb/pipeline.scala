package slamdata.engine.physical.mongodb

import slamdata.engine.Error

import com.mongodb.DBObject

import scalaz._
import Scalaz._

import slamdata.engine.{RenderTree, Terminal, NonTerminal}
import slamdata.engine.fp._

case class PipelineOpMergeError(left: PipelineOp, right: PipelineOp, hint: Option[String] = None) {
  def message = "The pipeline op " + left + " cannot be merged with the pipeline op " + right + hint.map(": " + _).getOrElse("")

  override lazy val hashCode = Set(left.hashCode, right.hashCode, hint.hashCode).hashCode

  override def equals(that: Any) = that match {
    case PipelineOpMergeError(l2, r2, h2) => (left == l2 && right == r2 || left == r2 && right == l2) && hint == h2
    case _ => false
  }
}

case class PipelineMergeError(merged: List[PipelineOp], lrest: List[PipelineOp], rrest: List[PipelineOp], hint: Option[String] = None) {
  def message = "The pipeline " + lrest + " cannot be merged with the pipeline " + rrest + hint.map(": " + _).getOrElse("")

  override lazy val hashCode = Set(merged.hashCode, lrest.hashCode, rrest.hashCode, hint.hashCode).hashCode

  override def equals(that: Any) = that match {
    case PipelineMergeError(m2, l2, r2, h2) => merged == m2 && (lrest == l2 && rrest == r2 || lrest == r2 && rrest == l2) && hint == h2
    case _ => false
  }
}

sealed trait MergePatchError extends Error
object MergePatchError {
  case class Pipeline(error: PipelineMergeError) extends MergePatchError {
    def message = error.message
  }
  case class Op(error: PipelineOpMergeError) extends MergePatchError {
    def message = error.message
  }
  case object NonEmpty extends MergePatchError {
    def message = "merge history not empty"
  }
}


private[mongodb] sealed trait MergePatch {
  import ExprOp.{And => EAnd, _}
  import PipelineOp._
  import MergePatch._

  /**
   * Combines the patches in sequence.
   */
  def >> (that: MergePatch): MergePatch = (this, that) match {
    case (left, Id) => left
    case (Id, right) => right

    case (x, y) => Then(x, y)
  }

  /**
   * Combines the patches in parallel.
   */
  def && (that: MergePatch): MergePatch = (this, that) match {
    case (x, y) if x == y => x

    case (x, y) => And(x, y)
  }

  def flatten: List[MergePatch.PrimitiveMergePatch]

  def applyVar(v: DocVar): DocVar

  def apply(op: PipelineOp): MergePatchError \/ (List[PipelineOp], MergePatch)

  private def genApply(op: PipelineOp)(applyVar0: PartialFunction[DocVar, DocVar]): MergePatchError \/ (List[PipelineOp], MergePatch) = {
    val applyVar = (f: DocVar) => applyVar0.lift(f).getOrElse(f)

    def applyExprOp(e: ExprOp): ExprOp = e.mapUp {
      case f : DocVar => applyVar(f)
    }

    def applyFieldName(name: BsonField): BsonField = {
      applyVar(DocField(name)).deref.getOrElse(name) // TODO: Delete field if it's transformed away to nothing???
    }

    def applySelector(s: Selector): Selector = s.mapUpFields(PartialFunction(applyFieldName _))

    def applyReshape(shape: Reshape): Reshape = shape match {
      case Reshape.Doc(value) => Reshape.Doc(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })

      case Reshape.Arr(value) => Reshape.Arr(value.transform {
        case (k, -\/(e)) => -\/(applyExprOp(e))
        case (k, \/-(r)) => \/-(applyReshape(r))
      })
    }

    def applyGrouped(grouped: Grouped): Grouped = Grouped(grouped.value.transform {
      case (k, groupOp) => applyExprOp(groupOp) match {
        case groupOp : GroupOp => groupOp
        case _ => sys.error("Transformation changed the type -- error!")
      }
    })

    def applyMap[A](m: Map[BsonField, A]): Map[BsonField, A] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyNel[A](m: NonEmptyList[(BsonField, A)]): NonEmptyList[(BsonField, A)] = m.map(t => applyFieldName(t._1) -> t._2)

    def applyFindQuery(q: FindQuery): FindQuery = {
      q.copy(
        query   = applySelector(q.query),
        max     = q.max.map(applyMap _),
        min     = q.min.map(applyMap _),
        orderby = q.orderby.map(applyNel _)
      )
    }

    \/- (op match {
      case Project(shape)     => (Project(applyReshape(shape)) :: Nil) -> Id // Patch is all consumed
      case Group(grouped, by) => (Group(applyGrouped(grouped), by.bimap(applyExprOp _, applyReshape _)) :: Nil) -> Id // Patch is all consumed
    
      case Match(s)       => (Match(applySelector(s)) :: Nil)  -> this // Patch is not consumed
      case Redact(e)      => (Redact(applyExprOp(e)) :: Nil)   -> this
      case v @ Limit(_)   => (v :: Nil)                        -> this
      case v @ Skip(_)    => (v :: Nil)                        -> this
      case v @ Unwind(f)  => (Unwind(applyVar(f)) :: Nil)      -> this
      case v @ Sort(l)    => (Sort(applyNel(l)) :: Nil)        -> this
      case v @ Out(_)     => (v :: Nil)                        -> this
      case g : GeoNear    => (g.copy(distanceField = applyFieldName(g.distanceField), query = g.query.map(applyFindQuery _)) :: Nil) -> this
    })
  }

  def applyAll(l: List[PipelineOp]): MergePatchError \/ (List[PipelineOp], MergePatch) = {
    type ErrorM[X] = MergePatchError \/ X

    (l.headOption map { h0 =>
      for {
        t <- this(h0)

        (hs, patch) = t

        t <-  (l.tail.foldLeftM[ErrorM, (List[PipelineOp], MergePatch)]((hs, patch)) {
                case ((acc, patch), op0) =>
                  for {
                    t <- patch.apply(op0)

                    (ops, patch2) = t
                  } yield (ops.reverse ::: acc) -> patch2
              })

        (acc, patch3) = t
      } yield (acc.reverse, patch3)
    }).getOrElse(\/-(l -> MergePatch.Id))
  }
}
object MergePatch {
  import ExprOp._

  implicit val MergePatchShow = new Show[MergePatch] {
    override def show(v: MergePatch): Cord = Cord(v.toString)
  }

  sealed trait PrimitiveMergePatch extends MergePatch
  case object Id extends PrimitiveMergePatch {
    def flatten: List[PrimitiveMergePatch] = this :: Nil

    def applyVar(v: DocVar): DocVar = v

    def apply(op: PipelineOp): MergePatchError \/ (List[PipelineOp], MergePatch) = \/- ((op :: Nil) -> Id)
  }
  case class Rename(from: DocVar, to: DocVar) extends PrimitiveMergePatch {
    def flatten: List[PrimitiveMergePatch] = this :: Nil

    private lazy val flatFrom = from.deref.toList.flatMap(_.flatten)
    private lazy val flatTo   = to.deref.toList.flatMap(_.flatten)

    private def replaceMatchingPrefix(f: Option[BsonField]): Option[BsonField] = f match {
      case None => None

      case Some(f) =>
        val l = f.flatten

        BsonField(if (l.startsWith(flatFrom)) flatTo ::: l.drop(flatFrom.length) else l)
    }

    def applyVar(v: DocVar): DocVar = v match {
      case DocVar(name, deref) if (name == from.name) => DocVar(to.name, replaceMatchingPrefix(deref))
    }

    def apply(op: PipelineOp): MergePatchError \/ (List[PipelineOp], MergePatch) = genApply(op) {
      case DocVar(name, deref) if (name == from.name) => DocVar(to.name, replaceMatchingPrefix(deref))
    }
  }
  sealed trait CompositeMergePatch extends MergePatch

  case class Then(fst: MergePatch, snd: MergePatch) extends CompositeMergePatch {
    def flatten: List[PrimitiveMergePatch] = fst.flatten ++ snd.flatten

    def applyVar(v: DocVar): DocVar = snd.applyVar(fst.applyVar(v))

    def apply(op: PipelineOp): MergePatchError \/ (List[PipelineOp], MergePatch) = {
      for {
        t <- fst(op)
        
        (ops, patchfst) = t

        t <- snd.applyAll(ops)

        (ops, patchsnd) = t
      } yield (ops, patchfst >> patchsnd)
    }
  }

  case class And(left: MergePatch, right: MergePatch) extends CompositeMergePatch {
    def flatten: List[PrimitiveMergePatch] = left.flatten ++ right.flatten

    def applyVar(v: DocVar): DocVar = {
      val lv = left.applyVar(v)
      var rv = right.applyVar(v)

      if (lv == v) rv
      else if (rv == v) lv
      else if (lv.toString.compareTo(rv.toString) < 0) lv else rv
    }

    def apply(op: PipelineOp): MergePatchError \/ (List[PipelineOp], MergePatch) = {
      for {
        t <- left(op)

        (lops, lp2) = t

        t <- right(op)

        (rops, rp2) = t

        merged0 <- PipelineOp.mergeOps(Nil, lops, lp2, rops, rp2).leftMap(MergePatchError.Pipeline.apply)

        (merged, lp3, rp3) = merged0
      } yield (merged, lp3 && rp3)
    }
  }
}

private[mongodb] sealed trait MergeResult {
  import MergeResult._

  def ops: List[PipelineOp]

  def leftPatch: MergePatch

  def rightPatch: MergePatch  

  def flip: MergeResult = this match {
    case Left (v, lp, rp) => Right(v, rp, lp)
    case Right(v, lp, rp) => Left (v, rp, lp)
    case Both (v, lp, rp) => Both (v, rp, lp)
  }

  def tuple: (List[PipelineOp], MergePatch, MergePatch) = (ops, leftPatch, rightPatch)
}
private[mongodb] object MergeResult {
  case class Left (ops: List[PipelineOp], leftPatch: MergePatch = MergePatch.Id, rightPatch: MergePatch = MergePatch.Id) extends MergeResult
  case class Right(ops: List[PipelineOp], leftPatch: MergePatch = MergePatch.Id, rightPatch: MergePatch = MergePatch.Id) extends MergeResult
  case class Both (ops: List[PipelineOp], leftPatch: MergePatch = MergePatch.Id, rightPatch: MergePatch = MergePatch.Id) extends MergeResult
}

final case class Pipeline(ops: List[PipelineOp]) {
  def repr: java.util.List[DBObject] = ops.foldLeft(new java.util.ArrayList[DBObject](): java.util.List[DBObject]) {
    case (list, op) =>
      list.add(op.bson.repr)

      list
  }

  def reverse: Pipeline = copy(ops = ops.reverse)

  def merge(that: Pipeline): PipelineMergeError \/ Pipeline = mergeM[Id](that).map(_._1).map(Pipeline(_))
  // def merge(that: Pipeline): PipelineMergeError \/ Pipeline = mergeM[Free.Trampoline](that).run.map(_._1).map(Pipeline(_))

  private def mergeM[F[_]](that: Pipeline)(implicit F: Monad[F]): F[PipelineMergeError \/ (List[PipelineOp], MergePatch, MergePatch)] = {
    PipelineOp.mergeOpsM[F](Nil, this.ops, MergePatch.Id, that.ops, MergePatch.Id).run
  }
}

object Pipeline {
  implicit def PipelineRenderTree(implicit RO: RenderTree[PipelineOp]) = new RenderTree[Pipeline] {
    override def render(p: Pipeline) = NonTerminal("Pipeline", p.ops.map(RO.render(_)))
  }
}

sealed trait PipelineSchema {
  def accum(op: PipelineOp): PipelineSchema = op match {
    case (p @ PipelineOp.Project(_)) => p.schema
    case (g @ PipelineOp.Group(_, _)) => g.schema
    case _ => this
  }
}
object PipelineSchema {
  import ExprOp.DocVar
  import PipelineOp.{Reshape, Project}

  def apply(ops: List[PipelineOp]): PipelineSchema = ops.foldLeft[PipelineSchema](Init)((s, o) => s.accum(o))

  case object Init extends PipelineSchema
  case class Succ(proj: Map[BsonField.Leaf, Unit \/ Succ]) extends PipelineSchema {
    private def toProject0(prefix: DocVar, s: Succ): Project = {
      def rec[A <: BsonField.Leaf](prefix: DocVar, x: A, y: Unit \/ Succ): (A, ExprOp \/ Reshape) = {
        x -> y.fold(_ => -\/ (prefix), s => \/- (s.toProject0(prefix, s).shape))
      }

      val indices = proj.collect {
        case (x : BsonField.Index, y) => 
          rec(prefix \ x, x, y)
      }

      val fields = proj.collect {
        case (x : BsonField.Name, y) =>
          rec(prefix \ x, x, y)
      }

      val arr = Reshape.Arr(indices)
      val doc = Reshape.Doc(fields)

      Project(arr ++ doc)
    }

    def toProject: Project = toProject0(DocVar.ROOT(), this)
  }
}

/**
 * A `PipelineBuilder` consists of a list of pipeline operations in *reverse*
 * order and a patch to be applied to all subsequent additions to the pipeline.
 *
 * This abstraction represents a work-in-progress, where more operations are 
 * expected to be patched and added to the pipeline. At some point, the `build`
 * method will be invoked to convert the `PipelineBuilder` into a `Pipeline`.
 */
case class PipelineBuilder private (buffer: List[PipelineOp], patch: MergePatch) {
  def build: Pipeline = Pipeline(buffer.reverse)

  def schema: PipelineSchema = PipelineSchema(buffer.reverse)

  def + (op: PipelineOp): MergePatchError \/ PipelineBuilder = {
    for {
      t <- patch(op)

      (ops2, patch2) = t
    } yield copy(buffer = ops2.reverse ::: buffer, patch2)
  }

  def ++ (ops: List[PipelineOp]): MergePatchError \/ PipelineBuilder = {
    type EitherE[X] = MergePatchError \/ X

    ops.foldLeftM[EitherE, PipelineBuilder](this) {
      case (pipe, op) => pipe + op
    }
  }

  def patch(patch2: MergePatch)(f: (MergePatch, MergePatch) => MergePatch): PipelineBuilder = copy(patch = f(patch, patch2))

  def patchSeq(patch2: MergePatch) = patch(patch2)(_ >> _)

  def patchPar(patch2: MergePatch) = patch(patch2)(_ && _)

  def fby(that: PipelineBuilder): MergePatchError \/ PipelineBuilder = {
    for {
      t <- patch.applyAll(that.buffer.reverse)

      (thatOps2, thisPatch2) = t
    } yield PipelineBuilder(thatOps2.reverse ::: this.buffer, thisPatch2 >> that.patch)
  }

  def merge0(that: PipelineBuilder): MergePatchError \/ (PipelineBuilder, MergePatch, MergePatch) = {
    for {
      t <- PipelineOp.mergeOps(Nil, this.buffer.reverse, this.patch, that.buffer.reverse, that.patch).leftMap(MergePatchError.Pipeline.apply)

      (ops, lp, rp) = t
    } yield (PipelineBuilder(ops.reverse, lp && rp), lp, rp)
  }

  def merge(that: PipelineBuilder): MergePatchError \/ PipelineBuilder = merge0(that).map(_._1)
}
object PipelineBuilder {
  def empty = PipelineBuilder(Nil, MergePatch.Id)

  def apply(p: PipelineOp): PipelineBuilder = PipelineBuilder(p :: Nil, MergePatch.Id)

  implicit def PipelineBuilderRenderTree(implicit RO: RenderTree[PipelineOp]) = new RenderTree[PipelineBuilder] {
    override def render(v: PipelineBuilder) = NonTerminal("PipelinBuilder", v.buffer.reverse.map(RO.render(_)))
  }
}

sealed trait PipelineOp {
  def bson: Bson.Doc

  def isShapePreservingOp: Boolean = this match {
    case x : PipelineOp.ShapePreservingOp => true
    case _ => false
  }

  def isNotShapePreservingOp: Boolean = !isShapePreservingOp

  def commutesWith(that: PipelineOp): Boolean = false

  def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult

  private def delegateMerge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that.merge(this).map(_.flip)
  
  private def mergeThisFirst: PipelineOpMergeError \/ MergeResult                   = \/- (MergeResult.Left(this :: Nil))
  private def mergeThatFirst(that: PipelineOp): PipelineOpMergeError \/ MergeResult = \/- (MergeResult.Right(that :: Nil))
  private def mergeThisAndDropThat: PipelineOpMergeError \/ MergeResult             = \/- (MergeResult.Both(this :: Nil))
  private def error(left: PipelineOp, right: PipelineOp, msg: String)               = -\/ (PipelineOpMergeError(left, right, Some(msg)))
}

object PipelineOp {
  def mergeOps(merged: List[PipelineOp], left: List[PipelineOp], lp0: MergePatch, right: List[PipelineOp], rp0: MergePatch): PipelineMergeError \/ (List[PipelineOp], MergePatch, MergePatch) = {
    mergeOpsM[Id](merged, left, lp0, right, rp0).run
    // mergeOpsM[Free.Trampoline](merged, left, lp0, right, rp0).run.run
  }

  private[PipelineOp] case class Patched(ops: List[PipelineOp], unpatch: PipelineOp) {
    def decon: Option[(PipelineOp, Patched)] = ops.headOption.map(head => head -> copy(ops = ops.tail))
  }

  private[PipelineOp] case class MergeState(patched0: Option[Patched], unpatched: List[PipelineOp], patch: MergePatch, schema: PipelineSchema) {
    def isEmpty: Boolean = patched0.map(_.ops.isEmpty).getOrElse(true)

    /**
     * Refills the patched op, if possible.
     */
    def refill: MergePatchError \/ MergeState = {
      if (isEmpty) unpatched match {
        case Nil => \/- (this)

        case x :: xs => 
          for {
            t <- patch(x)

            (ops, patch) = t 

            r <- copy(patched0 = Some(Patched(ops, x)), unpatched = xs, patch = patch).refill
          } yield r
      } else \/- (this)
    }

    /**
     * Patches an extracts out a single pipeline op, if such is possible.
     */
    def patchedHead: MergePatchError \/ Option[(PipelineOp, MergeState)] = {
      for {
        side <- refill
      } yield {
        for {
          patched <- side.patched0
          t       <- patched.decon

          (head, patched) = t
        } yield head -> copy(patched0 = if (patched.ops.length == 0) None else Some(patched), schema = schema.accum(head))
      }
    }

    /**
     * Appends the specified patch to this patch, repatching already patched ops (if any).
     */
    def patch(patch2: MergePatch): MergePatchError \/ MergeState = {
      val mpatch = patch >> patch2

      // We have to not only concatenate this patch with the existing patch,
      // but apply this patch to all of the ops already patched.
      patched0.map { patched =>
        for {
          t <- patch2.applyAll(patched.ops)

          (ops, patch2) = t
        } yield copy(Some(patched.copy(ops = ops)), patch = mpatch)
      }.getOrElse(\/- (copy(patch = mpatch)))
    }

    def replacePatch(patch2: MergePatch): MergePatchError \/ MergeState = 
      patched0 match {
        case None => \/- (copy(patch = patch2))
        case _ => -\/ (MergePatchError.NonEmpty)
      }

    def step(that: MergeState): MergePatchError \/ Option[(List[PipelineOp], MergeState, MergeState)] = {
      def mergeHeads(lh: PipelineOp, ls: MergeState, lt: MergeState, rh: PipelineOp, rs: MergeState, rt: MergeState) = {
        println("lh: " + lh)
        println("rh: " + rh)
        
        def construct(hs: List[PipelineOp]) = (ls: MergeState, rs: MergeState) => Some((hs, ls, rs))

        // One on left & right, merge them:
        for {
          mr <- lh.merge(rh).leftMap(MergePatchError.Op.apply)
          r  <- mr match {
                  case MergeResult.Left (hs, lp2, rp2) => (lt.patch(lp2) |@| rs.patch(rp2))(construct(hs))
                  case MergeResult.Right(hs, lp2, rp2) => (ls.patch(lp2) |@| rt.patch(rp2))(construct(hs))
                  case MergeResult.Both (hs, lp2, rp2) => (lt.patch(lp2) |@| rt.patch(rp2))(construct(hs))
                }
        } yield r
      }
      
      def reconstructRight(left: PipelineSchema, right: PipelineSchema, rp: MergePatch): 
          MergePatchError \/ (List[PipelineOp], MergePatch) = {
        def patchUp(proj: Project, rp2: MergePatch) = for {
          t <- rp(proj)
          (proj, rp) = t
        } yield (proj, rp >> rp2)

        right match {
          case s @ PipelineSchema.Succ(_) => patchUp(s.toProject, MergePatch.Id)
          
          case PipelineSchema.Init => 
            val uniqueField = left match {
              case PipelineSchema.Init => BsonField.genUniqName(Nil)
              case PipelineSchema.Succ(m) => BsonField.genUniqName(m.keys.map(_.toName))
            }
            val proj = Project(Reshape.Doc(Map(uniqueField -> -\/ (ExprOp.DocVar.ROOT()))))

            patchUp(proj, MergePatch.Rename(ExprOp.DocVar.ROOT(), ExprOp.DocField(uniqueField)))
        }
      }
      
      for {
        ls <- this.refill
        rs <- that.refill

        t1 <- ls.patchedHead
        t2 <- rs.patchedHead

        r  <- (t1, t2) match {
                case (None, None) => \/- (None)

                case (Some((out @ Out(_), lt)), None) =>
                  \/- (Some((out :: Nil, lt, rs)))
                
                case (Some((lh, lt)), None) => 
                  for {
                    t <- reconstructRight(this.schema, that.schema, rs.patch)
                    (rhs, rp) = t
                    rh :: Nil = rhs  // HACK!
                    rt <- rs.replacePatch(rp)
                    r <- mergeHeads(lh, ls, lt, rh, rs, rt)
                  } yield r

                case (None, Some((out @ Out(_), rt))) =>
                  \/- (Some((out :: Nil, ls, rt)))
                
                case (None, Some((rh, rt))) => 
                  for {
                    t <- reconstructRight(that.schema, this.schema, ls.patch)
                    (lhs, lp) = t
                    lh :: Nil = lhs  // HACK!
                    lt <- ls.replacePatch(lp)
                    r <- mergeHeads(lh, ls, lt, rh, rs, rt)
                  } yield r

                case (Some((lh, lt)), Some((rh, rt))) => mergeHeads(lh, ls, lt, rh, rs, rt)
              }
      } yield r
    }

    def rest: List[PipelineOp] = patched0.toList.flatMap(_.unpatch :: Nil) ::: unpatched
  }

  def mergeOpsM[F[_]: Monad](merged: List[PipelineOp], left: List[PipelineOp], lp0: MergePatch, right: List[PipelineOp], rp0: MergePatch): 
        EitherT[F, PipelineMergeError, (List[PipelineOp], MergePatch, MergePatch)] = {

    type M[X] = EitherT[F, PipelineMergeError, X]    

    def succeed[A](a: A): M[A] = a.point[M]
    def fail[A](e: PipelineMergeError): M[A] = EitherT((-\/(e): \/[PipelineMergeError, A]).point[F])

    def mergeOpsM0(merged: List[PipelineOp], left: MergeState, right: MergeState): 
          EitherT[F, PipelineMergeError, (List[PipelineOp], MergePatch, MergePatch)] = {

      // FIXME: Loss of information here (.rest) in case one op was patched 
      //        into many and one or more of the many were merged. Need to make
      //        things atomic.
      val convertError = (e: MergePatchError) => PipelineMergeError(merged, left.rest, right.rest)

      for {
        t <-  left.step(right).leftMap(convertError).fold(fail _, succeed _)
        r <-  t match {
                case None => succeed((merged, left.patch, right.patch))
                case Some((ops, left, right)) => mergeOpsM0(ops.reverse ::: merged, left, right)
              }
      } yield r 
    }

    for {
      t <- mergeOpsM0(merged, MergeState(None, left, lp0, PipelineSchema.Init), MergeState(None, right, rp0, PipelineSchema.Init))
      (ops, lp, rp) = t
    } yield (ops.reverse, lp, rp)
  }

  private def mergeGroupOnRight(field: ExprOp.DocVar)(right: Group): PipelineOpMergeError \/ MergeResult = {
    val Group(Grouped(m), b) = right

    // FIXME: Verify logic & test!!!
    val tmpName = BsonField.genUniqName(m.keys.map(_.toName))
    val tmpField = ExprOp.DocField(tmpName)

    val right2 = Group(Grouped(m + (tmpName -> ExprOp.Push(field))), b)
    val leftPatch = MergePatch.Rename(field, tmpField)

    \/- (MergeResult.Right(right2 :: Unwind(tmpField) :: Nil, leftPatch, MergePatch.Id))
  }

  sealed trait ShapePreservingOp extends PipelineOp

  private def mergeGroupOnLeft(field: ExprOp.DocVar)(left: Group): PipelineOpMergeError \/ MergeResult = {
    mergeGroupOnRight(field)(left).map(_.flip)
  }

  implicit def PiplineOpRenderTree(implicit RG: RenderTree[Grouped], RS: RenderTree[Selector])  = new RenderTree[PipelineOp] {
    def render(op: PipelineOp) = op match {
      case Project(Reshape.Doc(map)) => NonTerminal("Project", (map.map { case (name, x) => Terminal(name + " -> " + x) } ).toList)
      case Project(Reshape.Arr(map)) => NonTerminal("Project", (map.map { case (index, x) => Terminal(index + " -> " + x) } ).toList)
      case Group(grouped, by)        => NonTerminal("Group", RG.render(grouped) :: Terminal(by.toString) :: Nil)
      case Match(selector)           => NonTerminal("Match", RS.render(selector) :: Nil)
      case Sort(keys)                => NonTerminal("Sort", (keys.map { case (expr, ot) => Terminal(expr + " -> " + ot) } ).toList)
      case _                         => Terminal(op.toString)
    }
  }
  
  implicit def GroupedRenderTree = new RenderTree[Grouped] {
    def render(grouped: Grouped) = NonTerminal("Grouped", (grouped.value.map { case (name, expr) => Terminal(name + " -> " + expr) } ).toList)
  }
  
  private[PipelineOp] abstract sealed class SimpleOp(op: String) extends PipelineOp {
    def rhs: Bson

    def bson = Bson.Doc(Map(op -> rhs))
  }

  sealed trait Reshape {
    def toDoc: Reshape.Doc

    def bson: Bson.Doc

    def schema: PipelineSchema.Succ

    def nestField(name: String): Reshape.Doc = Reshape.Doc(Map(BsonField.Name(name) -> \/-(this)))

    def nestIndex(index: Int): Reshape.Arr = Reshape.Arr(Map(BsonField.Index(index) -> \/-(this)))

    def ++ (that: Reshape): Reshape = {
      implicit val sg = Semigroup.lastSemigroup[ExprOp \/ Reshape]

      (this, that) match {
        case (Reshape.Arr(m1), Reshape.Arr(m2)) => Reshape.Arr(m1 |+| m2)

        case (r1_, r2_) => 
          val r1 = r1_.toDoc 
          val r2 = r2_.toDoc

          Reshape.Doc(r1.value |+| r2.value)
      }
    }

    def merge(that: Reshape, path: ExprOp.DocVar): (Reshape, MergePatch) = {
      def mergeDocShapes(leftShape: Reshape.Doc, rightShape: Reshape.Doc, path: ExprOp.DocVar): (Reshape, MergePatch) = {
        rightShape.value.foldLeft((leftShape, MergePatch.Id: MergePatch)) { 
          case ((Reshape.Doc(shape), patch), (name, right)) => 
            shape.get(name) match {
              case None => (Reshape.Doc(shape + (name -> right)), patch)
      
              case Some(left) => (left, right) match {
                case (l, r) if (r == l) =>
                  (Reshape.Doc(shape), patch)
                
                case (\/- (l), \/- (r)) =>
                  val (mergedShape, innerPatch) = l.merge(r, path \ name)
                  (Reshape.Doc(shape + (name -> \/- (mergedShape))), innerPatch) 

                case _ =>
                  val tmpName = BsonField.genUniqName(shape.keySet)
                  (Reshape.Doc(shape + (tmpName -> right)), patch >> MergePatch.Rename(path \ name, (path \ tmpName)))
              }
            }
        }
      }

      def mergeArrShapes(leftShape: Reshape.Arr, rightShape: Reshape.Arr, path: ExprOp.DocVar): (Reshape.Arr, MergePatch) = {
        rightShape.value.foldLeft((leftShape, MergePatch.Id: MergePatch)) { 
          case ((Reshape.Arr(shape), patch), (name, right)) => 
            shape.get(name) match {
              case None => (Reshape.Arr(shape + (name -> right)), patch)
      
              case Some(left) => (left, right) match {
                case (l, r) if (r == l) =>
                  (Reshape.Arr(shape), patch)
                
                case (\/- (l), \/- (r)) =>
                  val (mergedShape, innerPatch) = l.merge(r, path \ name)
                  (Reshape.Arr(shape + (name -> \/- (mergedShape))), innerPatch) 

                case _ =>
                  val tmpName = BsonField.genUniqIndex(shape.keySet)
                  (Reshape.Arr(shape + (tmpName -> right)), patch >> MergePatch.Rename(path \ name, (path \ tmpName)))
              }
            }
        }
      }

      (this, that) match {
        case (r1 @ Reshape.Arr(_), r2 @ Reshape.Arr(_)) => mergeArrShapes(r1, r2, path)
        case (r1 @ Reshape.Doc(_), r2 @ Reshape.Doc(_)) => mergeDocShapes(r1, r2, path)
        case (r1, r2) => mergeDocShapes(r1.toDoc, r2.toDoc, path)
      }
    }
  }

  object Reshape {
    def unapply(v: Reshape): Option[Reshape] = Some(v)
    
    case class Doc(value: Map[BsonField.Name, ExprOp \/ Reshape]) extends Reshape {
      def schema: PipelineSchema.Succ = PipelineSchema.Succ(value.map {
        case (n, v) => (n: BsonField.Leaf) -> v.fold(_ => -\/ (()), r => \/-(r.schema))
      })

      def bson: Bson.Doc = Bson.Doc(value.map {
        case (field, either) => field.asText -> either.fold(_.bson, _.bson)
      })

      def toDoc = this
    }
    case class Arr(value: Map[BsonField.Index, ExprOp \/ Reshape]) extends Reshape {      
      def schema: PipelineSchema.Succ = PipelineSchema.Succ(value.map {
        case (n, v) => (n: BsonField.Leaf) -> v.fold(_ => -\/ (()), r => \/-(r.schema))
      })

      def bson: Bson.Doc = Bson.Doc(value.map {
        case (field, either) => field.asText -> either.fold(_.bson, _.bson)
      })

      def minIndex: Option[Int] = {
        val keys = value.keys

        keys.headOption.map(_ => keys.map(_.value).min)
      }

      def maxIndex: Option[Int] = {
        val keys = value.keys

        keys.headOption.map(_ => keys.map(_.value).max)
      }

      def offset(i0: Int) = Reshape.Arr(value.map {
        case (BsonField.Index(i), v) => BsonField.Index(i0 + i) -> v
      })

      def toDoc: Doc = Doc(value.map(t => t._1.toName -> t._2))

      // def flatten: (Map[BsonField.Index, ExprOp], Reshape.Arr)
    }

    implicit val ReshapeMonoid = new Monoid[Reshape] {
      def zero = Reshape.Arr(Map())

      def append(v10: Reshape, v20: => Reshape): Reshape = {
        val v1 = v10.toDoc
        val v2 = v20.toDoc

        val m1 = v1.value
        val m2 = v2.value
        val keys = m1.keySet ++ m2.keySet

        Reshape.Doc(keys.foldLeft(Map.empty[BsonField.Name, ExprOp \/ Reshape]) {
          case (map, key) =>
            val left  = m1.get(key)
            val right = m2.get(key)

            val result = ((left |@| right) {
              case (-\/(e1), -\/(e2)) => -\/ (e2)
              case (-\/(e1), \/-(r2)) => \/- (r2)
              case (\/-(r1), \/-(r2)) => \/- (append(r1, r2))
              case (\/-(r1), -\/(e2)) => -\/ (e2)
            }) orElse (left) orElse (right)

            map + (key -> result.get)
        })
      }
    }
  }

  case class Grouped(value: Map[BsonField.Leaf, ExprOp.GroupOp]) {
    def schema: PipelineSchema.Succ = PipelineSchema.Succ(value.mapValues(_ => -\/(())))

    def bson = Bson.Doc(value.map(t => t._1.asText -> t._2.bson))
  }
  case class Project(shape: Reshape) extends SimpleOp("$project") {
    def rhs = shape.bson

    def schema: PipelineSchema = shape.schema

    def id: Project = {
      def loop(prefix: Option[BsonField], p: Project): Project = {
        def nest(child: BsonField): BsonField = prefix.map(_ \ child).getOrElse(child)

        Project(p.shape match {
          case Reshape.Doc(m) => 
            Reshape.Doc(
              m.transform {
                case (k, v) =>
                  v.fold(
                    _ => -\/  (ExprOp.DocVar.ROOT(nest(k))),
                    r =>  \/- (loop(Some(nest(k)), Project(r)).shape)
                  )
              }
            )

          case Reshape.Arr(m) =>
            Reshape.Arr(
              m.transform {
                case (k, v) =>
                  v.fold(
                    _ => -\/  (ExprOp.DocVar.ROOT(nest(k))),
                    r =>  \/- (loop(Some(nest(k)), Project(r)).shape)
                  )
              }
            )
        })
      }

      loop(None, this)
    }

    def nestField(name: String): Project = Project(shape.nestField(name))

    def nestIndex(index: Int): Project = Project(shape.nestIndex(index))

    def ++ (that: Project): Project = Project(this.shape ++ that.shape)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = {
      that match {
        case Project(shape2) => 
          val (merged, patch) = this.shape.merge(shape2, ExprOp.DocVar.ROOT())

          \/- (MergeResult.Both(Project(merged) :: Nil, MergePatch.Id, patch))
          
        case Match(_)           => mergeThatFirst(that)
        case Redact(_)          => mergeThatFirst(that)
        case Limit(_)           => mergeThatFirst(that)
        case Skip(_)            => mergeThatFirst(that)
        case Unwind(_)          => mergeThatFirst(that) // TODO:
        case that @ Group(_, _) => mergeGroupOnRight(ExprOp.DocVar.ROOT())(that)
        case Sort(_)            => mergeThatFirst(that)
        case Out(_)             => mergeThisFirst
        case _: GeoNear         => mergeThatFirst(that)
      }
    }

    def field(name: String): Option[ExprOp \/ Project] = shape match {
      case Reshape.Doc(m) => m.get(BsonField.Name(name)).map { _ match {
          case e @ -\/(_) => e
          case     \/-(r) => \/- (Project(r))
        }
      }

      case _ => None
    }

    def index(idx: Int): Option[ExprOp \/ Project] = shape match {
      case Reshape.Arr(m) => m.get(BsonField.Index(idx)).map { _ match {
          case e @ -\/(_) => e
          case     \/-(r) => \/- (Project(r))
        }
      }

      case _ => None
    }
  }
  case class Match(selector: Selector) extends SimpleOp("$match") with ShapePreservingOp {
    def rhs = selector.bson

    private def mergeSelector(that: Match): Selector = 
      Selector.SelectorAndSemigroup.append(selector, that.selector)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case that: Match => \/- (MergeResult.Both(Match(mergeSelector(that)) :: Nil))
      case Redact(_)   => mergeThisFirst
      case Limit(_)    => mergeThatFirst(that)
      case Skip(_)     => mergeThatFirst(that)
      case Unwind(_)   => mergeThisFirst
      case Group(_, _) => mergeThisFirst   // FIXME: Verify logic & test!!!
      case Sort(_)     => mergeThisFirst
      case Out(_)      => mergeThisFirst
      case _: GeoNear  => mergeThatFirst(that)

      case _ => delegateMerge(that)
    }
  }
  case class Redact(value: ExprOp) extends SimpleOp("$redact") {
    def rhs = value.bson

    private def fields: List[ExprOp.DocVar] = {
      import scalaz.std.list._

      ExprOp.foldMap({
        case f : ExprOp.DocVar => f :: Nil
      })(value)
    }

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Redact(_) if (this == that) => mergeThisAndDropThat
      case Redact(_)                   => error(this, that, "Cannot merge multiple $redact ops") // FIXME: Verify logic & test!!!

      case Limit(_)                    => mergeThatFirst(that)
      case Skip(_)                     => mergeThatFirst(that)
      case Unwind(field) if (fields.contains(field))
                                       => error(this, that, "Cannot merge $redact with $unwind--condition refers to the field being unwound")
      case Unwind(_)                   => mergeThisFirst
      case that @ Group(_, _)          => mergeGroupOnRight(ExprOp.DocVar.ROOT())(that) // FIXME: Verify logic & test!!!
      case Sort(_)                     => mergeThatFirst(that)
      case Out(_)                      => mergeThisFirst
      case _: GeoNear                  => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }

  object Redact {
    val DESCEND = ExprOp.DocVar(ExprOp.DocVar.Name("DESCEND"),  None)
    val PRUNE   = ExprOp.DocVar(ExprOp.DocVar.Name("PRUNE"),    None)
    val KEEP    = ExprOp.DocVar(ExprOp.DocVar.Name("KEEP"),     None)
  }
  
case class Limit(value: Long) extends SimpleOp("$limit") with ShapePreservingOp {
    def rhs = Bson.Int64(value)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Limit(value) => \/- (MergeResult.Both(Limit(this.value max value) :: nil))
      case Skip(value)  => \/- (MergeResult.Both(Limit((this.value - value) max 0) :: that :: nil))
      case Unwind(_)   => mergeThisFirst
      case Group(_, _) => mergeThisFirst // FIXME: Verify logic & test!!!
      case Sort(_)     => mergeThisFirst
      case Out(_)      => mergeThisFirst
      case _: GeoNear  => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class Skip(value: Long) extends SimpleOp("$skip") with ShapePreservingOp {
    def rhs = Bson.Int64(value)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Skip(value) => \/- (MergeResult.Both(Skip(this.value min value) :: nil))
      case Unwind(_)   => mergeThisFirst
      case Group(_, _) => mergeThisFirst // FIXME: Verify logic & test!!!
      case Sort(_)     => mergeThisFirst
      case Out(_)      => mergeThisFirst
      case _: GeoNear  => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class Unwind(field: ExprOp.DocVar) extends SimpleOp("$unwind") {
    def rhs = Bson.Text(field.field.asField)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Unwind(_)     if (this == that)                                 => mergeThisAndDropThat
      case Unwind(field) if (this.field.field.asText < field.field.asText) => mergeThisFirst 
      case Unwind(_)                                                       => mergeThatFirst(that)
      
      case that @ Group(_, _) => mergeGroupOnRight(this.field)(that)   // FIXME: Verify logic & test!!!

      case Sort(_)            => mergeThatFirst(that)
      case Out(_)             => mergeThisFirst
      case _: GeoNear         => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class Group(grouped: Grouped, by: ExprOp \/ Reshape) extends SimpleOp("$group") {
    def schema: PipelineSchema = grouped.schema

    def rhs = {
      val Bson.Doc(m) = grouped.bson

      Bson.Doc(m + ("_id" -> by.fold(_.bson, _.bson)))
    }

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case that @ Group(grouped2, by2) => 
        if (this.hashCode <= that.hashCode) {
           // FIXME: Verify logic & test!!!
          val leftKeys = grouped.value.keySet
          val rightKeys = grouped2.value.keySet

          val allKeys = leftKeys union rightKeys

          val overlappingKeys = (leftKeys intersect rightKeys).toList
          val overlappingVars = overlappingKeys.map(ExprOp.DocField.apply)
          val newNames        = BsonField.genUniqNames(overlappingKeys.length, allKeys.map(_.toName))
          val newVars         = newNames.map(ExprOp.DocField.apply)

          val varMapping = overlappingVars.zip(newVars)
          val nameMap    = overlappingKeys.zip(newNames).toMap

          val rightRenamePatch = (varMapping.headOption.map { 
            case (old, new0) =>
              varMapping.tail.foldLeft[MergePatch](MergePatch.Rename(old, new0)) {
                case (patch, (old, new0)) => patch >> MergePatch.Rename(old, new0)
              }
          }).getOrElse(MergePatch.Id)

          val leftByName  = BsonField.Index(0)
          val rightByName = BsonField.Index(1)

          val mergedGroup = Grouped(grouped.value ++ grouped2.value.map {
            case (k, v) => nameMap.get(k).getOrElse(k) -> v
          })

          val mergedBy = if (by == by2) by else \/-(Reshape.Arr(Map(leftByName -> by, rightByName -> by2)))

          val merged = Group(mergedGroup, mergedBy)

          \/- (MergeResult.Both(merged :: Nil, MergePatch.Id, rightRenamePatch))
        } else delegateMerge(that)

      case Sort(_)     => mergeGroupOnLeft(ExprOp.DocVar.ROOT())(this) // FIXME: Verify logic & test!!!
      case Out(_)      => mergeThisFirst
      case _: GeoNear  => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class Sort(value: NonEmptyList[(BsonField, SortType)]) extends SimpleOp("$sort") with ShapePreservingOp {
    // Note: Map doesn't in general preserve the order of entries, which means we need a different representation for Bson.Doc.
    def rhs = Bson.Doc(Map((value.map { case (k, t) => k.asText -> t.bson }).list: _*))

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Sort(_) if (this == that) => mergeThisAndDropThat
      case Sort(_)                   => error(this, that, "Cannot merge multiple $sort ops")
      case Out(_)                    => mergeThisFirst
      case _: GeoNear                => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class Out(collection: Collection) extends SimpleOp("$out") with ShapePreservingOp {
    def rhs = Bson.Text(collection.name)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case Out(_) if (this == that) => mergeThisAndDropThat
      case Out(_)                   => error(this, that, "Cannot merge multiple $out ops")

      case _: GeoNear               => mergeThatFirst(that)
      
      case _ => delegateMerge(that)
    }
  }
  case class GeoNear(near: (Double, Double), distanceField: BsonField, 
                     limit: Option[Int], maxDistance: Option[Double],
                     query: Option[FindQuery], spherical: Option[Boolean],
                     distanceMultiplier: Option[Double], includeLocs: Option[BsonField],
                     uniqueDocs: Option[Boolean]) extends SimpleOp("$geoNear") {
    def rhs = Bson.Doc(List(
      List("near"           -> Bson.Arr(Bson.Dec(near._1) :: Bson.Dec(near._2) :: Nil)),
      List("distanceField"  -> distanceField.bson),
      limit.toList.map(limit => "limit" -> Bson.Int32(limit)),
      maxDistance.toList.map(maxDistance => "maxDistance" -> Bson.Dec(maxDistance)),
      query.toList.map(query => "query" -> query.bson),
      spherical.toList.map(spherical => "spherical" -> Bson.Bool(spherical)),
      distanceMultiplier.toList.map(distanceMultiplier => "distanceMultiplier" -> Bson.Dec(distanceMultiplier)),
      includeLocs.toList.map(includeLocs => "includeLocs" -> includeLocs.bson),
      uniqueDocs.toList.map(uniqueDocs => "uniqueDocs" -> Bson.Bool(uniqueDocs))
    ).flatten.toMap)

    def merge(that: PipelineOp): PipelineOpMergeError \/ MergeResult = that match {
      case _: GeoNear if (this == that) => mergeThisAndDropThat
      case _: GeoNear                   => error(this, that, "Cannot merge multiple $geoNear ops")
      
      case _ => delegateMerge(that)
    }
  }
}

sealed trait ExprOp {
  def bson: Bson

  import ExprOp._

  def mapUp(f0: PartialFunction[ExprOp, ExprOp]): ExprOp = {
    (mapUpM[Free.Trampoline](new PartialFunction[ExprOp, Free.Trampoline[ExprOp]] {
      def isDefinedAt(v: ExprOp) = f0.isDefinedAt(v)
      def apply(v: ExprOp) = f0(v).point[Free.Trampoline]
    })).run
  }

  // TODO: Port physical plan to fixplate to eliminate this madness!!!!!!!!!!!!!!!!!!!!!
  def mapUpM[F[_]](f0: PartialFunction[ExprOp, F[ExprOp]])(implicit F: Monad[F]): F[ExprOp] = {
    val f0l = f0.lift
    val f = (e: ExprOp) => f0l(e).getOrElse(e.point[F])

    def mapUp0(v: ExprOp): F[ExprOp] = {
      val rec = (v match {
        case Include            => v.point[F]
        case Exclude            => v.point[F]
        case DocVar(_, _)       => v.point[F]
        case Add(l, r)          => (mapUp0(l) |@| mapUp0(r))(Add(_, _))
        case AddToSet(_)        => v.point[F]
        case And(v)             => v.map(mapUp0 _).sequenceU.map(And(_))
        case SetEquals(l, r)       => (mapUp0(l) |@| mapUp0(r))(SetEquals(_, _))
        case SetIntersection(l, r) => (mapUp0(l) |@| mapUp0(r))(SetIntersection(_, _))
        case SetDifference(l, r)   => (mapUp0(l) |@| mapUp0(r))(SetDifference(_, _))
        case SetUnion(l, r)        => (mapUp0(l) |@| mapUp0(r))(SetUnion(_, _))
        case SetIsSubset(l, r)     => (mapUp0(l) |@| mapUp0(r))(SetIsSubset(_, _))
        case AnyElementTrue(v)     => mapUp0(v).map(AnyElementTrue(_))
        case AllElementsTrue(v)    => mapUp0(v).map(AllElementsTrue(_))
        case ArrayMap(a, b, c)  => (mapUp0(a) |@| mapUp0(c))(ArrayMap(_, b, _))
        case Avg(v)             => mapUp0(v).map(Avg(_))
        case Cmp(l, r)          => (mapUp0(l) |@| mapUp0(r))(Cmp(_, _))
        case Concat(a, b, cs)   => (mapUp0(a) |@| mapUp0(b) |@| cs.map(mapUp0 _).sequenceU)(Concat(_, _, _))
        case Cond(a, b, c)      => (mapUp0(a) |@| mapUp0(b) |@| mapUp0(c))(Cond(_, _, _))
        case DayOfMonth(a)      => mapUp0(a).map(DayOfMonth(_))
        case DayOfWeek(a)       => mapUp0(a).map(DayOfWeek(_))
        case DayOfYear(a)       => mapUp0(a).map(DayOfYear(_))
        case Divide(a, b)       => (mapUp0(a) |@| mapUp0(b))(Divide(_, _))
        case Eq(a, b)           => (mapUp0(a) |@| mapUp0(b))(Eq(_, _))
        case First(a)           => mapUp0(a).map(First(_))
        case Gt(a, b)           => (mapUp0(a) |@| mapUp0(b))(Gt(_, _))
        case Gte(a, b)          => (mapUp0(a) |@| mapUp0(b))(Gte(_, _))
        case Hour(a)            => mapUp0(a).map(Hour(_))
        case Meta                  => v.point[F]
        case Size(a)               => mapUp0(a).map(Size(_))
        case IfNull(a, b)       => (mapUp0(a) |@| mapUp0(b))(IfNull(_, _))
        case Last(a)            => mapUp0(a).map(Last(_))
        case Let(a, b)          => 
          type MapDocVarName[X] = Map[ExprOp.DocVar.Name, X]

          (Traverse[MapDocVarName].sequence[F, ExprOp](a.map(t => t._1 -> mapUp0(t._2))) |@| mapUp0(b))(Let(_, _))

        case Literal(_)         => v.point[F]
        case Lt(a, b)           => (mapUp0(a) |@| mapUp0(b))(Lt(_, _))
        case Lte(a, b)          => (mapUp0(a) |@| mapUp0(b))(Lte(_, _))
        case Max(a)             => mapUp0(a).map(Max(_))
        case Millisecond(a)     => mapUp0(a).map(Millisecond(_))
        case Min(a)             => mapUp0(a).map(Min(_))
        case Minute(a)          => mapUp0(a).map(Minute(_))
        case Mod(a, b)          => (mapUp0(a) |@| mapUp0(b))(Mod(_, _))
        case Month(a)           => mapUp0(a).map(Month(_))
        case Multiply(a, b)     => (mapUp0(a) |@| mapUp0(b))(Multiply(_, _))
        case Neq(a, b)          => (mapUp0(a) |@| mapUp0(b))(Neq(_, _))
        case Not(a)             => mapUp0(a).map(Not(_))
        case Or(a)              => a.map(mapUp0 _).sequenceU.map(Or(_))
        case Push(a)            => v.point[F]
        case Second(a)          => mapUp0(a).map(Second(_))
        case Strcasecmp(a, b)   => (mapUp0(a) |@| mapUp0(b))(Strcasecmp(_, _))
        case Substr(a, b, c)    => mapUp0(a).map(Substr(_, b, c))
        case Subtract(a, b)     => (mapUp0(a) |@| mapUp0(b))(Subtract(_, _))
        case Sum(a)             => mapUp0(a).map(Sum(_))
        case ToLower(a)         => mapUp0(a).map(ToLower(_))
        case ToUpper(a)         => mapUp0(a).map(ToUpper(_))
        case Week(a)            => mapUp0(a).map(Week(_))
        case Year(a)            => mapUp0(a).map(Year(_))
      }) 

      rec >>= f
    }

    mapUp0(this)
  }
}

object ExprOp {
  implicit object ExprOpRenderTree extends RenderTree[ExprOp] {
    override def render(v: ExprOp) = Terminal(v.toString)  // TODO
  }

  def children(expr: ExprOp): List[ExprOp] = expr match {
    case Include               => Nil
    case Exclude               => Nil
    case DocVar(_, _)          => Nil
    case Add(l, r)             => l :: r :: Nil
    case AddToSet(_)           => Nil
    case And(v)                => v.toList
    case SetEquals(l, r)       => l :: r :: Nil
    case SetIntersection(l, r) => l :: r :: Nil
    case SetDifference(l, r)   => l :: r :: Nil
    case SetUnion(l, r)        => l :: r :: Nil
    case SetIsSubset(l, r)     => l :: r :: Nil
    case AnyElementTrue(v)     => v :: Nil
    case AllElementsTrue(v)    => v :: Nil
    case ArrayMap(a, _, c)     => a :: c :: Nil
    case Avg(v)                => v :: Nil
    case Cmp(l, r)             => l :: r :: Nil
    case Concat(a, b, cs)      => a :: b :: cs
    case Cond(a, b, c)         => a :: b :: c :: Nil
    case DayOfMonth(a)         => a :: Nil
    case DayOfWeek(a)          => a :: Nil
    case DayOfYear(a)          => a :: Nil
    case Divide(a, b)          => a :: b :: Nil
    case Eq(a, b)              => a :: b :: Nil
    case First(a)              => a :: Nil
    case Gt(a, b)              => a :: b :: Nil
    case Gte(a, b)             => a :: b :: Nil
    case Hour(a)               => a :: Nil
    case Meta                  => Nil
    case Size(a)               => a :: Nil
    case IfNull(a, b)          => a :: b :: Nil
    case Last(a)               => a :: Nil
    case Let(_, b)             => b :: Nil
    case Literal(_)            => Nil
    case Lt(a, b)              => a :: b :: Nil
    case Lte(a, b)             => a :: b :: Nil
    case Max(a)                => a :: Nil
    case Millisecond(a)        => a :: Nil
    case Min(a)                => a :: Nil
    case Minute(a)             => a :: Nil
    case Mod(a, b)             => a :: b :: Nil
    case Month(a)              => a :: Nil
    case Multiply(a, b)        => a :: b :: Nil
    case Neq(a, b)             => a :: b :: Nil
    case Not(a)                => a :: Nil
    case Or(a)                 => a.toList
    case Push(a)               => Nil
    case Second(a)             => a :: Nil
    case Strcasecmp(a, b)      => a :: b :: Nil
    case Substr(a, _, _)       => a :: Nil
    case Subtract(a, b)        => a :: b :: Nil
    case Sum(a)                => a :: Nil
    case ToLower(a)            => a :: Nil
    case ToUpper(a)            => a :: Nil
    case Week(a)               => a :: Nil
    case Year(a)               => a :: Nil
  }

  def foldMap[Z: Monoid](f0: PartialFunction[ExprOp, Z])(v: ExprOp): Z = {
    val f = (e: ExprOp) => f0.lift(e).getOrElse(Monoid[Z].zero)
    Monoid[Z].append(f(v), Foldable[List].foldMap(children(v))(foldMap(f0)))
  }

  private[ExprOp] abstract sealed class SimpleOp(op: String) extends ExprOp {
    def rhs: Bson

    def bson = Bson.Doc(Map(op -> rhs))
  }

  sealed trait IncludeExclude extends ExprOp
  case object Include extends IncludeExclude {
    def bson = Bson.Int32(1)
  }
  case object Exclude extends IncludeExclude {
    def bson = Bson.Int32(0)
  }

  sealed trait FieldLike extends ExprOp {
    def field: BsonField
  }
  object DocField {
    def apply(field: BsonField): DocVar = DocVar.ROOT(field)

    def unapply(docVar: DocVar): Option[BsonField] = docVar match {
      case DocVar.ROOT(tail) => tail
      case _ => None
    }
  }
  case class DocVar(name: DocVar.Name, deref: Option[BsonField]) extends FieldLike {
    def field: BsonField = BsonField.Name(name.name) \\ deref.toList.flatMap(_.flatten)

    def bson = this match {
      case DocVar(DocVar.ROOT, Some(deref)) => Bson.Text(deref.asField)
      case _                                => Bson.Text(field.asVar)
    }

    def nestsWith(that: DocVar): Boolean = this.name == that.name

    def \ (that: DocVar): Option[DocVar] = (this, that) match {
      case (DocVar(n1, f1), DocVar(n2, f2)) if (n1 == n2) => 
        val f3 = (f1 |@| f2)(_ \ _) orElse (f1) orElse (f2)

        Some(DocVar(n1, f3))

      case _ => None
    }

    def \ (field: BsonField): DocVar = copy(deref = Some(deref.map(_ \ field).getOrElse(field)))
  }
  object DocVar {
    case class Name(name: String) {
      def apply() = DocVar(this, None)

      def apply(field: BsonField) = DocVar(this, Some(field))

      def apply(deref: Option[BsonField]) = DocVar(this, deref)

      def apply(leaves: List[BsonField.Leaf]) = DocVar(this, BsonField(leaves))

      def unapply(v: DocVar): Option[Option[BsonField]] = Some(v.deref)
    }
    val ROOT    = Name("ROOT")
    val CURRENT = Name("CURRENT")
  }

  sealed trait GroupOp extends ExprOp
  case class AddToSet(field: DocVar) extends SimpleOp("$addToSet") with GroupOp {
    def rhs = field.bson
  }
  case class Push(field: DocVar) extends SimpleOp("$push") with GroupOp {
    def rhs = field.bson
  }
  case class First(value: ExprOp) extends SimpleOp("$first") with GroupOp {
    def rhs = value.bson
  }
  case class Last(value: ExprOp) extends SimpleOp("$last") with GroupOp {
    def rhs = value.bson
  }
  case class Max(value: ExprOp) extends SimpleOp("$max") with GroupOp {
    def rhs = value.bson
  }
  case class Min(value: ExprOp) extends SimpleOp("$min") with GroupOp {
    def rhs = value.bson
  }
  case class Avg(value: ExprOp) extends SimpleOp("$avg") with GroupOp {
    def rhs = value.bson
  }
  case class Sum(value: ExprOp) extends SimpleOp("$sum") with GroupOp {
    def rhs = value.bson
  }
  object Count extends Sum(Literal(Bson.Int32(1)))

  sealed trait BoolOp extends ExprOp
  case class And(values: NonEmptyList[ExprOp]) extends SimpleOp("$and") with BoolOp {
    def rhs = Bson.Arr(values.list.map(_.bson))
  }
  case class Or(values: NonEmptyList[ExprOp]) extends SimpleOp("$or") with BoolOp {
    def rhs = Bson.Arr(values.list.map(_.bson))
  }
  case class Not(value: ExprOp) extends SimpleOp("$not") with BoolOp {
    def rhs = value.bson
  }

  sealed trait BinarySetOp extends ExprOp {
    def left: ExprOp
    def right: ExprOp
    
    def rhs = Bson.Arr(left.bson :: right.bson :: Nil)
  }
  case class SetEquals(left: ExprOp, right: ExprOp) extends SimpleOp("$setEquals") with BinarySetOp
  case class SetIntersection(left: ExprOp, right: ExprOp) extends SimpleOp("$setIntersection") with BinarySetOp
  case class SetDifference(left: ExprOp, right: ExprOp) extends SimpleOp("$setDifference") with BinarySetOp
  case class SetUnion(left: ExprOp, right: ExprOp) extends SimpleOp("$setUnion") with BinarySetOp
  case class SetIsSubset(left: ExprOp, right: ExprOp) extends SimpleOp("$setIsSubset") with BinarySetOp

  sealed trait UnarySetOp extends ExprOp {
    def value: ExprOp
    
    def rhs = value.bson
  }
  case class AnyElementTrue(value: ExprOp) extends SimpleOp("$anyElementTrue") with UnarySetOp
  case class AllElementsTrue(value: ExprOp) extends SimpleOp("$allElementsTrue") with UnarySetOp

  sealed trait CompOp extends ExprOp {
    def left: ExprOp    
    def right: ExprOp

    def rhs = Bson.Arr(left.bson :: right.bson :: Nil)
  }
  case class Cmp(left: ExprOp, right: ExprOp) extends SimpleOp("$cmp") with CompOp
  case class Eq(left: ExprOp, right: ExprOp) extends SimpleOp("$eq") with CompOp
  case class Gt(left: ExprOp, right: ExprOp) extends SimpleOp("$gt") with CompOp
  case class Gte(left: ExprOp, right: ExprOp) extends SimpleOp("$gte") with CompOp
  case class Lt(left: ExprOp, right: ExprOp) extends SimpleOp("$lt") with CompOp
  case class Lte(left: ExprOp, right: ExprOp) extends SimpleOp("$lte") with CompOp
  case class Neq(left: ExprOp, right: ExprOp) extends SimpleOp("$ne") with CompOp

  sealed trait MathOp extends ExprOp {
    def left: ExprOp
    def right: ExprOp

    def rhs = Bson.Arr(left.bson :: right.bson :: Nil)
  }
  case class Add(left: ExprOp, right: ExprOp) extends SimpleOp("$add") with MathOp
  case class Divide(left: ExprOp, right: ExprOp) extends SimpleOp("$divide") with MathOp
  case class Mod(left: ExprOp, right: ExprOp) extends SimpleOp("$mod") with MathOp
  case class Multiply(left: ExprOp, right: ExprOp) extends SimpleOp("$multiply") with MathOp
  case class Subtract(left: ExprOp, right: ExprOp) extends SimpleOp("$subtract") with MathOp

  sealed trait StringOp extends ExprOp
  case class Concat(first: ExprOp, second: ExprOp, others: List[ExprOp]) extends SimpleOp("$concat") with StringOp {
    def rhs = Bson.Arr(first.bson :: second.bson :: others.map(_.bson))
  }
  case class Strcasecmp(left: ExprOp, right: ExprOp) extends SimpleOp("$strcasecmp") with StringOp {
    def rhs = Bson.Arr(left.bson :: right.bson :: Nil)
  }
  case class Substr(value: ExprOp, start: Int, count: Int) extends SimpleOp("$substr") with StringOp {
    def rhs = Bson.Arr(value.bson :: Bson.Int32(start) :: Bson.Int32(count) :: Nil)
  }
  case class ToLower(value: ExprOp) extends SimpleOp("$toLower") with StringOp {
    def rhs = value.bson
  }
  case class ToUpper(value: ExprOp) extends SimpleOp("$toUpper") with StringOp {
    def rhs = value.bson
  }

  sealed trait TextSearchOp extends ExprOp
  case object Meta extends SimpleOp("$meta") with TextSearchOp {
    def rhs = Bson.Text("textScore")
  }

  sealed trait ArrayOp extends ExprOp
  case class Size(array: ExprOp) extends SimpleOp("$size") with ArrayOp {
    def rhs = array.bson
  }

  sealed trait ProjOp extends ExprOp
  case class ArrayMap(input: ExprOp, as: String, in: ExprOp) extends SimpleOp("$map") with ProjOp {
    def rhs = Bson.Doc(Map(
      "input" -> input.bson,
      "as"    -> Bson.Text(as),
      "in"    -> in.bson
    ))
  }
  case class Let(vars: Map[DocVar.Name, ExprOp], in: ExprOp) extends SimpleOp("$let") with ProjOp {
    def rhs = Bson.Doc(Map(
      "vars" -> Bson.Doc(vars.map(t => (t._1.name, t._2.bson))),
      "in"   -> in.bson
    ))
  }
  case class Literal(value: Bson) extends ProjOp {
    def bson = value match {
      case Bson.Text(str) if (str.startsWith("$")) => Bson.Doc(Map("$literal" -> value))
      
      case Bson.Doc(value)                         => Bson.Doc(value.transform((_, x) => Literal(x).bson))
      case Bson.Arr(value)                         => Bson.Arr(value.map(x => Literal(x).bson))
      
      case _                                       => value
    }
  }

  sealed trait DateOp extends ExprOp {
    def date: ExprOp

    def rhs = date.bson
  }
  case class DayOfYear(date: ExprOp) extends SimpleOp("$dayOfYear") with DateOp
  case class DayOfMonth(date: ExprOp) extends SimpleOp("$dayOfMonth") with DateOp
  case class DayOfWeek(date: ExprOp) extends SimpleOp("$dayOfWeek") with DateOp
  case class Year(date: ExprOp) extends SimpleOp("$year") with DateOp
  case class Month(date: ExprOp) extends SimpleOp("$month") with DateOp
  case class Week(date: ExprOp) extends SimpleOp("$week") with DateOp
  case class Hour(date: ExprOp) extends SimpleOp("$hour") with DateOp
  case class Minute(date: ExprOp) extends SimpleOp("$minute") with DateOp
  case class Second(date: ExprOp) extends SimpleOp("$second") with DateOp
  case class Millisecond(date: ExprOp) extends SimpleOp("$millisecond") with DateOp

  sealed trait CondOp extends ExprOp
  case class Cond(predicate: ExprOp, ifTrue: ExprOp, ifFalse: ExprOp) extends SimpleOp("$cond") with CondOp {
    def rhs = Bson.Arr(predicate.bson :: ifTrue.bson :: ifFalse.bson :: Nil)
  }
  case class IfNull(expr: ExprOp, replacement: ExprOp) extends CondOp {
    def bson = Bson.Arr(expr.bson :: replacement.bson :: Nil)
  }
}
