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

package quasar.physical.mongodbq.expression

import quasar.Predef._

import matryoshka.Recursive.ops._
import org.scalacheck._
import org.specs2.mutable._
import org.specs2.scalaz._

import quasar.physical.mongodbq.{Bson, BsonField}

object ArbitraryExprOp {

  lazy val genExpr: Gen[Expression] = Gen.const($literal(Bson.Int32(1)))
}

class ExpressionSpec extends Specification with DisjunctionMatchers {

  "Expression" should {

    "escape literal string with $" in {
      val x = Bson.Text("$1")
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape literal string with no leading '$'" in {
      val x = Bson.Text("abc")
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple integer literal" in {
      val x = Bson.Int32(0)
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple array literal" in {
      val x = Bson.Arr(Bson.Text("abc") :: Bson.Int32(0) :: Nil)
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape string nested in array" in {
      val x = Bson.Arr(Bson.Text("$1") :: Nil)
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape simple doc literal" in {
      val x = Bson.Doc(ListMap("a" -> Bson.Text("b")))
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "escape string nested in doc" in {
      val x = Bson.Doc(ListMap("a" -> Bson.Text("$1")))
      $literal(x).cata(bsonƒ) must_== Bson.Doc(ListMap("$literal" -> x))
    }

    "render $$ROOT" in {
      DocVar.ROOT().bson must_== Bson.Text("$$ROOT")
    }

    "treat DocField as alias for DocVar.ROOT()" in {
      DocField(BsonField.Name("foo")) must_== DocVar.ROOT(BsonField.Name("foo"))
    }

    "render $foo under $$ROOT" in {
      DocVar.ROOT(BsonField.Name("foo")).bson must_== Bson.Text("$foo")
    }

    "render $foo.bar under $$CURRENT" in {
      DocVar.CURRENT(BsonField.Name("foo") \ BsonField.Name("bar")).bson must_== Bson.Text("$$CURRENT.foo.bar")
    }
  }

  "toJs" should {
    import org.threeten.bp._
    import quasar.jscore._

    "handle addition with epoch date literal" in {
      toJs(
        $add(
          $literal(Bson.Date(Instant.ofEpochMilli(0))),
          $var(DocField(BsonField.Name("epoch"))))) must beRightDisjunction(
        JsFn(JsFn.defaultName, New(Name("Date"), List(Select(Ident(JsFn.defaultName), "epoch")))))
    }
  }
}
