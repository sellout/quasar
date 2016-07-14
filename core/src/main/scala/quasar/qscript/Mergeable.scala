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
import quasar.namegen._

import matryoshka._
import matryoshka.patterns._
import simulacrum.typeclass
import scalaz._, Scalaz._

@typeclass trait Mergeable[F[_]] {
  type IT[F[_]]

  def mergeSrcs(fm1: FreeMap[IT], fm2: FreeMap[IT], a1: EnvT[Ann[IT], F, Unit], a2: EnvT[Ann[IT], F, Unit]):
      OptionT[State[NameGen, ?], SrcMerge[EnvT[Ann[IT], F, Unit], FreeMap[IT]]]
}

object Mergeable {
  type Aux[T[_[_]], F[_]] = Mergeable[F] { type IT[F[_]] = T[F] }

  implicit def const[T[_[_]]: EqualT]: Mergeable.Aux[T, Const[DeadEnd, ?]] =
    new Mergeable[Const[DeadEnd, ?]] {
      type IT[F[_]] = T[F]

      def mergeSrcs(
        left: FreeMap[T],
        right: FreeMap[T],
        p1: EnvT[Ann[T], Const[DeadEnd, ?], Unit],
        p2: EnvT[Ann[T], Const[DeadEnd, ?], Unit]) =
        OptionT(state(
          (p1 ≟ p2).option(SrcMerge[EnvT[Ann[T], Const[DeadEnd, ?], Unit], FreeMap[IT]](p1, left, right))))
    }

  implicit def coproduct[T[_[_]], F[_], G[_]](
    implicit mf: Mergeable.Aux[T, F],
             mg: Mergeable.Aux[T, G]):
      Mergeable.Aux[T, Coproduct[F, G, ?]] =
    new Mergeable[Coproduct[F, G, ?]] {
      type IT[F[_]] = T[F]

      def mergeSrcs(
        left: FreeMap[IT],
        right: FreeMap[IT],
        cp1: EnvT[Ann[IT], Coproduct[F, G, ?], Unit],
        cp2: EnvT[Ann[IT], Coproduct[F, G, ?], Unit]) = ??? // {
      //   (cp1.run, cp2.run) match {
      //     case (-\/(left1), -\/(left2)) =>
      //       mf.mergeSrcs(left, right, left1, left2).map {
      //         case SrcMerge(src, left, right) => SrcMerge(Coproduct(-\/(src)), left, right)
      //       }
      //     case (\/-(right1), \/-(right2)) =>
      //       mg.mergeSrcs(left, right, right1, right2).map {
      //         case SrcMerge(src, left, right) => SrcMerge(Coproduct(\/-(src)), left, right)
      //       }
      //     case (_, _) => OptionT.none
      //   }
      // }
    }


}

