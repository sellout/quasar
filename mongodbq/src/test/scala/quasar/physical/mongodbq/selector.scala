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

import org.specs2.mutable._

class SelectorSpec extends Specification  {

  implicit def toBson(x: Int) = Bson.Int32(x)
  implicit def toField(name: String) = BsonField.Name(name)

  "Selector" should {
    import Selector._

    "bson" should {
      "render simple expr" in {
        Expr(Lt(10)).bson must_== Bson.Doc(ListMap("$lt" -> 10))
      }

      "render $not expr" in {
        NotExpr(Lt(10)).bson must_== Bson.Doc(ListMap("$not" -> Bson.Doc(ListMap("$lt" -> 10))))
      }

      "render simple selector" in {
        val sel = Doc(BsonField.Name("foo") -> Gt(10))

        sel.bson must_== Bson.Doc(ListMap("foo" -> Bson.Doc(ListMap("$gt" -> 10))))
      }

      "render simple selector with path" in {
        val sel = Doc(
          BsonField.Name("foo") \ BsonField.Name("3") \ BsonField.Name("bar") -> Gt(10)
        )

        sel.bson must_== Bson.Doc(ListMap("foo.3.bar" -> Bson.Doc(ListMap("$gt" -> 10))))
      }

      "render flattened $and" in {
        val cs = And(
          Doc(BsonField.Name("foo") -> Gt(10)),
          And(
            Doc(BsonField.Name("foo") -> Lt(20)),
            Doc(BsonField.Name("foo") -> Neq(15))
          )
        )
        cs.bson must_==
          Bson.Doc(ListMap("$and" -> Bson.Arr(List(
            Bson.Doc(ListMap("foo" -> Bson.Doc(ListMap("$gt" -> 10)))),
            Bson.Doc(ListMap("foo" -> Bson.Doc(ListMap("$lt" -> 20)))),
            Bson.Doc(ListMap("foo" -> Bson.Doc(ListMap("$ne" -> 15))))
          ))))
      }

      "render not(eq(...))" in {
        val cond = NotExpr(Eq(10))
        cond.bson must_== Bson.Doc(ListMap("$ne" -> 10))
      }
    }

    "negate" should {
      "rewrite singleton Doc" in {
        val sel = Doc(
          BsonField.Name("x") -> Lt(10),
          BsonField.Name("y") -> Gt(10))
        sel.negate must_== Or(
          Doc(ListMap(("x": BsonField) -> NotExpr(Lt(10)))),
          Doc(ListMap(("y": BsonField) -> NotExpr(Gt(10)))))
      }

      "rewrite And" in {
        val sel =
          And(
            Doc(BsonField.Name("x") -> Lt(10)),
            Doc(BsonField.Name("y") -> Gt(10)))
        sel.negate must_== Or(
          Doc(ListMap(("x": BsonField) -> NotExpr(Lt(10)))),
          Doc(ListMap(("y": BsonField) -> NotExpr(Gt(10)))))
      }

      "rewrite Doc" in {
        val sel = Doc(
          BsonField.Name("x") -> Lt(10),
          BsonField.Name("y") -> Gt(10))
        sel.negate must_== Or(
          Doc(ListMap(("x": BsonField) -> NotExpr(Lt(10)))),
          Doc(ListMap(("y": BsonField) -> NotExpr(Gt(10)))))
      }
    }

    "constructors" should {
      "define nested $and and $or" in {
        val cs =
          Or(
            And(
              Doc(BsonField.Name("foo") -> Gt(10)),
              Doc(BsonField.Name("foo") -> Lt(20))
            ),
            And(
              Doc(BsonField.Name("bar") -> Gte(1)),
              Doc(BsonField.Name("bar") -> Lte(5))
            )
          )

        1 must_== 1
      }
    }
  }
}
