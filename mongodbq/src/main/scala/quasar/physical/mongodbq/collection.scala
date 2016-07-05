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
import quasar.{RenderTree, Terminal}
import quasar.fp._
import quasar.fs._

import scala.util.parsing.combinator._

import scalaz._, Scalaz._
import pathy.Path.{dir => pDir, file => pFile, _}

final case class Collection(databaseName: String, collectionName: String) {
  import Collection._

  /** Convert this collection to a file. */
  def asFile: AFile = {
    val db   = DatabaseNameUnparser(databaseName)
    val segs = CollectionNameUnparser(collectionName).reverse
    val f    = segs.headOption getOrElse db

    (segs ::: List(db)).drop(1).foldRight(rootDir)((d, p) => p </> pDir(d)) </> pFile(f)
  }
}

object Collection {

  def fromPath(path: APath): PathError \/ Collection = {
    import PathError._

    val collResult = for {
      tpl  <- dbNameAndRest(path)
      (db, r) = tpl
      ss   <- r.toNel.toRightDisjunction("path names a database, but no collection")
      segs <- ss.traverse(CollectionSegmentParser(_))
      coll =  segs.toList mkString "."
      len  =  utf8length(db) + 1 + utf8length(coll)
      _    <- if (len > 120)
                s"database+collection name too long ($len > 120 bytes): $db.$coll".left
              else ().right
    } yield Collection(db, coll)

    collResult leftMap (invalidPath(path, _))
  }

  /** Returns the database name determined by the given path. */
  def dbNameFromPath(path: APath): PathError \/ String =
    dbNameAndRest(path) bimap (PathError.invalidPath(path, _), _._1)

  /** Returns the directory name derived from the given database name. */
  def dirNameFromDbName(dbName: String): DirName =
    DirName(DatabaseNameUnparser(dbName))

  private def dbNameAndRest(path: APath): String \/ (String, IList[String]) =
    flatten(None, None, None, Some(_), Some(_), path)
      .toIList.unite.uncons(
        "no database specified".left,
        (h, t) => DatabaseNameParser(h) strengthR t)

  private trait PathParser extends RegexParsers {
    override def skipWhitespace = false

    protected def substitute(pairs: List[(String, String)]): Parser[String] =
      pairs.foldLeft[Parser[String]](failure("no match")) {
        case (acc, (a, b)) => (a ^^ κ(b)) | acc
      }
  }

  def utf8length(str: String) = str.getBytes("UTF-8").length

  val DatabaseNameEscapes = List(
    " "  -> "+",
    "."  -> "~",
    "%"  -> "%%",
    "+"  -> "%add",
    "~"  -> "%tilde",
    "/"  -> "%div",
    "\\" -> "%esc",
    "\"" -> "%quot",
    "*"  -> "%mul",
    "<"  -> "%lt",
    ">"  -> "%gt",
    ":"  -> "%colon",
    "|"  -> "%bar",
    "?"  -> "%qmark")

  private object DatabaseNameParser extends PathParser {
    def name: Parser[String] =
      char.* ^^ { _.mkString }

    def char: Parser[String] = substitute(DatabaseNameEscapes) | "(?s).".r

    def apply(input: String): String \/ String = parseAll(name, input) match {
      case Success(name, _) if utf8length(name) > 64 =>
        s"database name too long (> 64 bytes): $name".left
      case Success(name, _) =>
        name.right
      case failure : NoSuccess =>
        s"failed to parse ‘$input’: ${failure.msg}".left
    }
  }

  private object DatabaseNameUnparser extends PathParser {
    def name = nameChar.* ^^ { _.mkString }

    def nameChar = substitute(DatabaseNameEscapes.map(_.swap)) | "(?s).".r

    def apply(input: String): String = parseAll(name, input) match {
      case Success(result, _) => result
      case failure : NoSuccess => scala.sys.error("doesn't happen")
    }
  }

  val CollectionNameEscapes = List(
    "."  -> "\\.",
    "$"  -> "\\d",
    "\\" -> "\\\\")

  private object CollectionSegmentParser extends PathParser {
    def seg: Parser[String] =
      char.* ^^ { _.mkString }

    def char: Parser[String] = substitute(CollectionNameEscapes) | "(?s).".r

    /**
      * @return If implemented correctly, should always return a [[String]] in the right hand of the [[Disjunction]]
      */
    def apply(input: String): String \/ String = parseAll(seg, input) match {
      case Success(seg, _) =>
        seg.right
      case failure : NoSuccess =>
        s"failed to parse ‘$input’: ${failure.msg}".left
    }
  }

  private object CollectionNameUnparser extends PathParser {
    def name = repsep(seg, ".")

    def seg = segChar.* ^^ { _.mkString }

    def segChar = substitute(CollectionNameEscapes.map(_.swap)) | "(?s)[^.]".r

    def apply(input: String): List[String] = parseAll(name, input) match {
      case Success(result, _) => result
      case failure : NoSuccess => scala.sys.error("doesn't happen")
    }
  }

  implicit val order: Order[Collection] =
    Order.orderBy(c => (c.databaseName, c.collectionName))

  implicit val renderTree: RenderTree[Collection] =
    new RenderTree[Collection] {
      def render(v: Collection) =
        Terminal(List("Collection"), Some(v.databaseName + "; " + v.collectionName))
    }
}
