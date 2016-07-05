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

package quasar.physical.mongodbq

import quasar.Predef._
import quasar.effect.Failure
import quasar.fp._

import java.lang.{Boolean => JBoolean}
import java.util.LinkedList
import scala.Predef.classOf
import scala.collection.JavaConverters._

import com.mongodb._
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.model._
import com.mongodb.async._
import com.mongodb.async.client._
import org.bson.BsonDocument
import scalaz.{Failure => _, _}, Scalaz._
import scalaz.concurrent.Task
import scalaz.stream._

final class MongoDbIO[A] private (protected val r: ReaderT[Task, MongoClient, A]) {
  def map[B](f: A => B): MongoDbIO[B] =
    new MongoDbIO(r map f)

  def flatMap[B](f: A => MongoDbIO[B]): MongoDbIO[B] =
    new MongoDbIO(r flatMap (a => f(a).r))

  def attempt: MongoDbIO[Throwable \/ A] =
    new MongoDbIO(r mapK (_.attempt))

  def attemptMongo: MongoErrT[MongoDbIO, A] =
    EitherT(attempt flatMap {
      case -\/(me: MongoException) => me.left.point[MongoDbIO]
      case -\/(t)                  => MongoDbIO.fail(t)
      case \/-(a)                  => a.right.point[MongoDbIO]
    })

  def run(c: MongoClient): Task[A] =
    r.run(c)

  def runF[S[_]](
    c: MongoClient
  )(implicit
    S0: Task :<: S,
    S1: MongoErr :<: S
  ): Free[S, A] = {
    val mongoErr = Failure.Ops[MongoException, S]
    mongoErr.unattempt(free.lift(attemptMongo.run.run(c)).into[S])
  }
}

object MongoDbIO {

  /** Returns the stream of results of aggregating documents according to the
    * given aggregation pipeline.
    */
  def aggregated(
    src: Collection,
    pipeline: List[Bson.Doc],
    allowDiskUse: Boolean
  ): Process[MongoDbIO, BsonDocument] =
    aggregateIterable(src, pipeline, allowDiskUse)
      .map(_.useCursor(new JBoolean(true)))
      .liftM[Process]
      .flatMap(iterableToProcess)

  /** Aggregates documents according to the given aggregation pipeline, which
    * must end with an `\$out` stage specifying the collection where results
    * may be found.
    */
  def aggregate(
    src: Collection,
    pipeline: List[Bson.Doc],
    allowDiskUse: Boolean
  ): MongoDbIO[Unit] =
    aggregateIterable(src, pipeline, allowDiskUse)
      .flatMap(c => async[java.lang.Void](c.toCollection(_)))
      .void

  def collectionExists(c: Collection): MongoDbIO[Boolean] =
    collectionsIn(c.databaseName)
      .exists(_.collectionName == c.collectionName)
      .runLastOr(false)

  /** All discoverable collections on the server. */
  def collections: Process[MongoDbIO, Collection] =
    databaseNames flatMap collectionsIn

  /** The collections in the named database. */
  def collectionsIn(dbName: String): Process[MongoDbIO, Collection] =
    database(dbName).liftM[Process]
      .flatMap(db => iterableToProcess(db.listCollectionNames))
      .map(Collection(dbName, _))

  /** Creates the given collection. */
  def createCollection(c: Collection): MongoDbIO[Unit] =
    database(c.databaseName) flatMap (db =>
      async[java.lang.Void](db.createCollection(c.collectionName, _)).void)

  /** Names of all discoverable databases on the server. */
  def databaseNames: Process[MongoDbIO, String] =
    client.liftM[Process]
      .flatMap(c => iterableToProcess(c.listDatabaseNames))
      .onFailure {
        case t: MongoCommandException =>
          credentials.liftM[Process]
            .flatMap(ys => Process.emitAll(ys.map(_.getSource).distinct))

        case t =>
          Process.fail(t)
      }

  def dropCollection(c: Collection): MongoDbIO[Unit] =
    collection(c) flatMap (mc => async(mc.drop).void)

  def dropDatabase(named: String): MongoDbIO[Unit] =
    database(named) flatMap (d => async(d.drop).void)

  def dropAllDatabases: MongoDbIO[Unit] =
    databaseNames.map(dropDatabase).eval.run

  /** Ensure the given collection exists, creating it if not. */
  def ensureCollection(c: Collection): MongoDbIO[Unit] =
    collectionExists(c).ifM(().point[MongoDbIO], createCollection(c))

  /** Returns the name of the first database where an insert to the collection
    * having the given name succeeds.
    */
  def firstWritableDb(collName: String): OptionT[MongoDbIO, String] = {
    type M[A] = OptionT[MongoDbIO, A]

    val testDoc = Bson.Doc(ListMap("a" -> Bson.Int32(1)))

    def canWriteToCol(coll: Collection): M[String] =
      insertAny[Id](coll, testDoc.repr)
        .filter(_ == 1)
        .as(coll.databaseName)
        .attempt
        .flatMap(r => OptionT(r.toOption.point[MongoDbIO]))

    databaseNames
      .translate[M](liftMT[MongoDbIO, OptionT])
      .evalMap(n => canWriteToCol(Collection(n, collName)))
      .take(1).runLast
      .flatMap(n => OptionT(n.point[MongoDbIO]))
  }

  /** Inserts the given documents into the collection. */
  def insert[F[_]: Foldable](coll: Collection, docs: F[BsonDocument]): MongoDbIO[Unit] = {
    val docList = new LinkedList[BsonDocument]
    val insertOpts = (new InsertManyOptions()).ordered(false)

    Foldable[F].traverse_(docs)(d => docList.add(d): Id[Boolean])

    if (docList.isEmpty)
      ().point[MongoDbIO]
    else
      collection(coll)
        .flatMap(c => async[java.lang.Void](c.insertMany(docList, insertOpts, _)))
        .void
  }

  /** Attempts to insert as many of the given documents into the collection as
    * possible. The number of documents inserted is returned, if possible, and
    * may be smaller than the original amount if any documents failed to insert.
    */
  def insertAny[F[_]: Foldable](coll: Collection, docs: F[BsonDocument]): OptionT[MongoDbIO, Int] = {
    val docList = new LinkedList[WriteModel[BsonDocument]]
    val writeOpts = (new BulkWriteOptions()).ordered(false)

    Foldable[F].traverse_(docs)(d => docList.add(new InsertOneModel(d)): Id[Boolean])

    if (docList.isEmpty)
      OptionT.none
    else
      OptionT(collection(coll)
        .flatMap(c => async[BulkWriteResult](c.bulkWrite(docList, writeOpts, _)))
        .map(r => r.wasAcknowledged option r.getInsertedCount))
  }

  /** Returns the results of executing the map-reduce job described by `cfg`
    * on the documents from `src`.
    */
  def mapReduced(src: Collection, cfg: MapReduce): Process[MongoDbIO, BsonDocument] =
    mapReduceIterable(src, cfg)
      .liftM[Process]
      .flatMap(iterableToProcess)

  /** Executes the map-reduce job described by `cfg`, sourcing documents from
    * `src` and writing the output to `dst`.
    */
  def mapReduce(
    src: Collection,
    dst: MapReduce.OutputCollection,
    cfg: MapReduce
  ): MongoDbIO[Unit] = {
    import MapReduce._, Action._

    type F[A] = State[MapReduceIterable[BsonDocument], A]
    val ms = MonadState[F, MapReduceIterable[BsonDocument]]

    def withAction(actOut: ActionedOutput): F[Unit] =
      actOut.databaseName.traverse_[F](n => ms.modify(_.databaseName(n)))     *>
      actOut.shardOutputCollection.traverse_[F](s => ms.modify(_.sharded(s))) *>
      actOut.action.nonAtomic.traverse_[F](s => ms.modify(_.nonAtomic(s)))    *>
      ms.modify(_.action(actOut.action match {
        case Replace   => MapReduceAction.REPLACE
        case Merge(_)  => MapReduceAction.MERGE
        case Reduce(_) => MapReduceAction.REDUCE
      }))

    mapReduceIterable(src, cfg) flatMap { it =>
      val itWithOutput =
        dst.withAction.traverse_(withAction)
          .exec(it.collectionName(dst.collectionName))

      async[java.lang.Void](itWithOutput.toCollection).void
    }
  }

  /** Rename `src` to `dst` using the given semantics. */
  def rename(src: Collection, dst: Collection, semantics: RenameSemantics): MongoDbIO[Unit] = {
    import RenameSemantics._

    val dropDst = semantics match {
      case Overwrite    => true
      case FailIfExists => false
    }

    if (src === dst)
      ().point[MongoDbIO]
    else
      collection(src)
        .flatMap(c => async[java.lang.Void](c.renameCollection(
          new MongoNamespace(dst.databaseName, dst.collectionName),
          (new RenameCollectionOptions) dropTarget dropDst,
          _)))
        .void
  }

  /** Returns the version of the MongoDB server the client is connected to. */
  def serverVersion: MongoDbIO[List[Int]] = {
    def lookupVersion(dbName: String): MongoDbIO[MongoException \/ List[Int]] = {
      val cmd = Bson.Doc(ListMap("buildinfo" -> Bson.Int32(1)))

      runCommand(dbName, cmd).attemptMongo.run map (_ flatMap (doc =>
        Option(doc getString "version")
          .toRightDisjunction(new MongoException("Unable to determine server version, buildInfo response is missing the 'version' field"))
          .map(_.getValue.split('.').toList.map(_.toInt))))
    }

    val finalize: ((Vector[MongoException], Vector[List[Int]])) => MongoDbIO[List[Int]] = {
      case (errs, vers) =>
        vers.headOption.map(_.point[MongoDbIO]) orElse
        errs.headOption.map(fail[List[Int]])  getOrElse
        fail(new MongoException("No database found."))
    }

    // NB: use "admin" DB as fallback if no database is known to exist.
    (databaseNames ++ Process.emit("admin"))
      .evalMap(lookupVersion)
      .takeThrough(_.isLeft)
      .runLog
      .map(_.toVector.separate)
      .flatMap(finalize)
  }

  def fail[A](t: Throwable): MongoDbIO[A] =
    liftTask(Task.fail(t))

  def runNT(client: MongoClient): MongoDbIO ~> Task =
    new (MongoDbIO ~> Task) {
      def apply[A](m: MongoDbIO[A]) = m.run(client)
    }

  val liftTask: Task ~> MongoDbIO =
    new (Task ~> MongoDbIO) {
      def apply[A](t: Task[A]) = lift(_ => t)
    }

  /** Returns the underlying, configured aggregate iterable for applying the
    * given pipeline to the source collection.
    */
  private[mongodbq] def aggregateIterable(
    src: Collection,
    pipeline: List[Bson.Doc],
    allowDiskUse: Boolean
  ): MongoDbIO[AggregateIterable[BsonDocument]] =
    collection(src) map (c =>
      c.aggregate(pipeline.asJava)
        .allowDiskUse(new JBoolean(allowDiskUse)))

  private[mongodbq] def mapReduceIterable(
    src: Collection,
    cfg: MapReduce
  ): MongoDbIO[MapReduceIterable[BsonDocument]] = {
    type IT       = MapReduceIterable[BsonDocument]
    type CfgIt[A] = State[IT, A]
    val ms = MonadState[CfgIt, IT]

    def foldIt[A](a: Option[A])(f: (IT, A) => IT): CfgIt[Unit] =
      ms.modify(a.foldLeft(_)(f))

    val nonEmptyScope =
      cfg.scope.nonEmpty option cfg.scope

    val sortRepr =
      cfg.inputSort map (ts =>
        Bson.Doc(ListMap(
          ts.list.toList.map(_.bimap(_.asText, sortDirToBson(_))): _*
        )).repr)

    val configuredIt =
      foldIt(cfg.selection)((i, s) => i.filter(s.bson.repr))           *>
      foldIt(sortRepr)(_ sort _)                                       *>
      foldIt(cfg.limit)(_ limit _.toInt)                               *>
      foldIt(cfg.finalizer)((i, f) => i.finalizeFunction(f.pprint(0))) *>
      foldIt(nonEmptyScope)((i, s) => i.scope(Bson.Doc(s).repr))       *>
      foldIt(cfg.jsMode)(_ jsMode _)                                   *>
      foldIt(cfg.verbose)(_ verbose _)

    collection(src) map { c =>
      configuredIt exec c.mapReduce(cfg.map.pprint(0), cfg.reduce.pprint(0))
    }
  }

  private[mongodbq] def find(c: Collection): MongoDbIO[FindIterable[BsonDocument]] =
    collection(c) map (_.find)

  private[mongodbq] def async[A](f: SingleResultCallback[A] => Unit): MongoDbIO[A] =
    liftTask(Task.async(cb => f(new DisjunctionCallback(cb))))

  implicit val mongoDbInstance: Monad[MongoDbIO] with Catchable[MongoDbIO] =
    new Monad[MongoDbIO] with Catchable[MongoDbIO] {
      override def map[A, B](fa: MongoDbIO[A])(f: A => B) = fa map f
      def point[A](a: => A) = new MongoDbIO(Kleisli(_ => Task.now(a)))
      def bind[A, B](fa: MongoDbIO[A])(f: A => MongoDbIO[B]) = fa flatMap f
      def fail[A](t: Throwable) = MongoDbIO.fail(t)
      def attempt[A](fa: MongoDbIO[A]) = fa.attempt
    }

  ////

  private def apply[A](f: MongoClient => A): MongoDbIO[A] =
    lift(c => Task.delay(f(c)))

  private def lift[A](f: MongoClient => Task[A]): MongoDbIO[A] =
    new MongoDbIO(Kleisli(f))

  private def client: MongoDbIO[MongoClient] =
    MongoDbIO(ι)

  // TODO: Make a basic credential type in scala and expose this method.
  private val credentials: MongoDbIO[List[MongoCredential]] =
    MongoDbIO(_.getSettings.getCredentialList.asScala.toList)

  private[mongodbq] def collection(c: Collection): MongoDbIO[MongoCollection[BsonDocument]] =
    database(c.databaseName).map(_.getCollection(c.collectionName, classOf[BsonDocument]))

  private def database(named: String): MongoDbIO[MongoDatabase] =
    MongoDbIO(_ getDatabase named)

  private def runCommand(dbName: String, cmd: Bson.Doc): MongoDbIO[BsonDocument] =
    database(dbName) flatMap (db => async[BsonDocument](db.runCommand(cmd, classOf[BsonDocument], _)))

  private def iterableToProcess[A](it: MongoIterable[A]): Process[MongoDbIO, A] = {
    def go(c: AsyncBatchCursor[A]): Process[MongoDbIO, A] =
      Process.eval(async(c.next))
        .flatMap(r => Option(r).cata(
          as => Process.emitAll(as.asScala.toVector) ++ go(c),
          Process.halt))

    Process.eval(async(it.batchCursor)) flatMap (cur =>
      go(cur) onComplete Process.eval_(MongoDbIO(_ => cur.close())))
  }

  private final class DisjunctionCallback[A](f: Throwable \/ A => Unit)
    extends SingleResultCallback[A] {

    def onResult(result: A, error: Throwable): Unit =
      f(Option(error) <\/ result)
  }
}
