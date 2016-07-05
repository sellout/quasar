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

package quasar.physical.mongodbq.accumulator

import quasar.Predef._
import quasar.fp._

import org.scalacheck._
import org.specs2.scalaz._
import scalaz._, Scalaz._
import scalaz.scalacheck.ScalazProperties._
import shapeless.contrib.scalaz.instances.{deriveShow => _, _}

class AccumulatorSpec extends Spec {
  implicit val arbAccumOp: Arbitrary ~> λ[α => Arbitrary[AccumOp[α]]] =
    new (Arbitrary ~> λ[α => Arbitrary[AccumOp[α]]]) {
      def apply[α](arb: Arbitrary[α]): Arbitrary[AccumOp[α]] =
        Arbitrary(arb.arbitrary.flatMap(a =>
          Gen.oneOf(
            $addToSet(a),
            $push(a),
            $first(a),
            $last(a),
            $max(a),
            $min(a),
            $avg(a),
            $sum(a))))
    }

  implicit val arbIntAccumOp = arbAccumOp(Arbitrary.arbInt)

  checkAll(comonad.laws[AccumOp])
  checkAll(traverse1.laws[AccumOp])
}
