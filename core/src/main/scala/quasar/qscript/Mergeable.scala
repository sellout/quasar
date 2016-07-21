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

package quasar.qscript

import quasar.Predef._
import quasar.fp._

import matryoshka._
import matryoshka.patterns._
import simulacrum.typeclass
import scalaz._, Scalaz._

@typeclass trait Mergeable[F[_]] {
  type IT[F[_]]

  def mergeSrcs(fm1: FreeMap[IT], fm2: FreeMap[IT], a1: EnvT[Ann[IT], F, Hole], a2: EnvT[Ann[IT], F, Hole]):
      Option[SrcMerge[EnvT[Ann[IT], F, Hole], FreeMap[IT]]]
}

object Mergeable {
  type Aux[T[_[_]], F[_]] = Mergeable[F] { type IT[F[_]] = T[F] }

  implicit def const[T[_[_]]: EqualT]: Mergeable.Aux[T, Const[DeadEnd, ?]] =
    new Mergeable[Const[DeadEnd, ?]] {
      type IT[F[_]] = T[F]

      // NB: I think it is true that the buckets on p1 and p2 _must_ be equal
      //     for us to even get to this point, so we can always pick one
      //     arbitrarily for the result.
      def mergeSrcs(
        left: FreeMap[T],
        right: FreeMap[T],
        p1: EnvT[Ann[T], Const[DeadEnd, ?], Hole],
        p2: EnvT[Ann[T], Const[DeadEnd, ?], Hole]) =
        (p1 ≟ p2).option(SrcMerge[EnvT[Ann[T], Const[DeadEnd, ?], Hole], FreeMap[IT]](p1, left, right))
    }

  implicit def coproduct[T[_[_]], F[_], G[_]](
    implicit F: Mergeable.Aux[T, F], G: Mergeable.Aux[T, G],
             FC: F :<: Coproduct[F, G, ?], GC: G :<: Coproduct[F, G, ?]):
      Mergeable.Aux[T, Coproduct[F, G, ?]] =
    new Mergeable[Coproduct[F, G, ?]] {
      type IT[F[_]] = T[F]

      def mergeSrcs(
        left: FreeMap[IT],
        right: FreeMap[IT],
        cp1: EnvT[Ann[IT], Coproduct[F, G, ?], Hole],
        cp2: EnvT[Ann[IT], Coproduct[F, G, ?], Hole]) =
        (cp1.lower.run, cp2.lower.run) match {
          case (-\/(left1), -\/(left2)) =>
            F.mergeSrcs(left, right, EnvT((cp1.ask, left1)), EnvT((cp2.ask, left2))).map {
              case SrcMerge(src, left, right) =>
                SrcMerge(envtHmap(FC)(src), left, right)
            }
          case (\/-(right1), \/-(right2)) =>
            G.mergeSrcs(left, right, EnvT((cp1.ask, right1)), EnvT((cp2.ask, right2))).map {
              case SrcMerge(src, left, right) =>
                SrcMerge(envtHmap(GC)(src), left, right)
            }
          case (_, _) => None
        }
    }
}

