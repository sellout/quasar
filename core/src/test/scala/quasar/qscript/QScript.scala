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
import quasar.{LogicalPlan, Data, CompilerHelpers}
import quasar.{LogicalPlan => LP}
import quasar.fp._
import quasar.fs._
import quasar.qscript.MapFuncs._
import quasar.std.StdLib._

import matryoshka._, FunctorT.ops._
import org.specs2.scalaz._
import pathy.Path._
//import shapeless.contrib.scalaz.instances.deriveEqual
import scalaz._
import Scalaz._

class QScriptSpec extends CompilerHelpers with ScalazMatchers {
  val transform = new Transform[Fix]
  import transform._

  val optimize = new Optimize[Fix]
  import optimize._

  def callIt(lp: Fix[LP]): Inner =
    lp.transCata(lpToQScript)
       .transCata(liftFG(elideNopJoins[QScriptPure[Fix, ?]]))
       .transCata(liftFG(elideNopMaps[QScriptPure[Fix, ?]]))
       .transCata(liftFF(coalesceMap[QScriptPure[Fix, ?]]))

  def RootR = CorecursiveOps[Fix, QScriptPure[Fix, ?]](E.inj(Const[DeadEnd, Inner](Root))).embed

  def ProjectFieldR[A](src: FreeMap[Fix], field: FreeMap[Fix]): FreeMap[Fix] =
    Free.roll(ProjectField(src, field))

  def StrR[A](s: String): FreeMap[Fix] = Free.roll(StrLit(s))

  def lpRead(path: String): Fix[LP] =
    LogicalPlan.Read(sandboxAbs(posixCodec.parseAbsFile(path).get))

  "replan" should {
    "convert a very simple read" in {
      callIt(lpRead("/foo")) must
      equal(
        F.inj(Map(RootR, ProjectFieldR(UnitF, StrR("foo")))).embed)
    }

    "convert a simple read" in {
      callIt(lpRead("/some/foo/bar")) must
      equal(
        F.inj(
          Map(RootR,
            ProjectFieldR(
              ProjectFieldR(
                ProjectFieldR(
                  UnitF,
                  StrR("some")),
                StrR("foo")),
              StrR("bar")))).embed)

      // Map(Root, ObjectProject(ObjectProject(ObjectProject((), "some"), "foo"), "bar"))
    }

    "convert a basic invoke" in {
      callIt(math.Add(lpRead("/foo"), lpRead("/bar")).embed) must
      equal(
        F.inj(
          Map(RootR,
            Free.roll(Add(
              ProjectFieldR(UnitF, StrR("foo")),
              ProjectFieldR(UnitF, StrR("bar")))))).embed)
    }

    "convert basic join" in {
      //"select foo.name, bar.address from foo join bar on foo.id = bar.foo_id",

      val lp = LP.Let('__tmp0, lpRead("/foo"),
        LP.Let('__tmp1, lpRead("/bar"),
          LP.Let('__tmp2,
            set.InnerJoin[FLP](LP.Free('__tmp0), LP.Free('__tmp1),
              relations.Eq[FLP](
                structural.ObjectProject(LP.Free('__tmp0), LP.Constant(Data.Str("id"))),
                structural.ObjectProject(LP.Free('__tmp1), LP.Constant(Data.Str("foo_id"))))),
            makeObj(
              "name" ->
                structural.ObjectProject[FLP](
                  structural.ObjectProject(LP.Free('__tmp2), LP.Constant(Data.Str("left"))),
                  LP.Constant(Data.Str("name"))),
              "address" ->
                structural.ObjectProject[FLP](
                  structural.ObjectProject(LP.Free('__tmp2), LP.Constant(Data.Str("right"))),
                  LP.Constant(Data.Str("address")))))))
      callIt(lp) must equal(F.inj(Map(RootR, ProjectFieldR(UnitF, StrR("foo")))).embed)
    }
  }
}
