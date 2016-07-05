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

package quasar.physical.mongodbq.fs

import quasar.Predef._
import quasar.fs._
import quasar.physical.mongodbq._

import pathy.Path._
import scalaz._, Scalaz._

object fsops {
  type MongoFsM[A]  = FileSystemErrT[MongoDbIO, A]

  import FileSystemError._, PathError._

  /** The collections having a prefix equivalent to the given directory path. */
  def collectionsInDir(dir: ADir): MongoFsM[Vector[Collection]] =
    for {
      dbName <- dbNameFromPathM(dir)
      cName  <- collFromPathM(dir)
                  .map(_.collectionName)
                  .getOrElse("")
                  .liftM[FileSystemErrT]
      cs     <- MongoDbIO.collectionsIn(dbName)
                  .filter(_.collectionName startsWith cName)
                  .runLog.map(_.toVector).liftM[FileSystemErrT]
      _      <- if (cs.isEmpty) pathErr(pathNotFound(dir)).raiseError[MongoFsM, Unit]
                else ().point[MongoFsM]
    } yield cs

  /** The user (non-system) collections having a prefix equivalent to the given
    * directory path.
    */
  def userCollectionsInDir(dir: ADir): MongoFsM[Vector[Collection]] =
    collectionsInDir(dir) map (_.filterNot(_.collectionName startsWith "system."))

  /** A filesystem `PathSegment` representing the first segment of a collection name
    * relative to the given parent directory.
    */
  def collectionPathSegment(parent: ADir): Collection => Option[PathSegment] =
    _.asFile relativeTo parent flatMap firstSegmentName

  /** The collection represented by the given path. */
  def collFromPathM(path: APath): MongoFsM[Collection] =
    EitherT(Collection.fromPath(path).leftMap(pathErr(_)).point[MongoDbIO])

  /** The database referred to by the given path. */
  def dbNameFromPathM(path: APath): MongoFsM[String] =
    EitherT(Collection.dbNameFromPath(path).leftMap(pathErr(_)).point[MongoDbIO])

  /** An error indicating that the directory refers to an ancestor of `/`.
    *
    * TODO: This would be eliminated if we switched to AbsDir everywhere and
    *       disallowed AbsDirs like "/../foo" by construction. Revisit this once
    *       scala-pathy has been updated.
    */
  def nonExistentParent[A](dir: ADir): MongoFsM[A] =
    pathErr(invalidPath(dir, "directory refers to nonexistent parent"))
      .raiseError[MongoFsM, A]
}
