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

import scala.Predef.implicitly

import matryoshka._
import org.specs2.scalaz._
import pathy.Path._
import scalaz._, Scalaz._

class QScriptSpec extends CompilerHelpers with ScalazMatchers {
  val transform = new Transform[Fix, QScriptProject[Fix, ?]]

  // TODO: Narrow this to QScriptPure
  type QS[A] = QScriptProject[Fix, A]
  val DE = implicitly[Const[DeadEnd, ?] :<: QS]
  val QC = implicitly[QScriptCore[Fix, ?] :<: QS]

  def RootR = CorecursiveOps[Fix, QS](DE.inj(Const[DeadEnd, Fix[QS]](Root))).embed

  def ProjectFieldR[A](src: FreeMap[Fix], field: FreeMap[Fix]): FreeMap[Fix] =
    Free.roll(ProjectField(src, field))

  def lpRead(path: String): Fix[LP] =
    LogicalPlan.Read(sandboxAbs(posixCodec.parseAbsFile(path).get))

  // TODO instead of calling `.toOption` on the `\/`
  // write an `Equal[PlannerError]` and test for specific errors too
  "replan" should {
    "convert a constant boolean" in {
       QueryFile.convertToQScript(LogicalPlan.Constant(Data.Bool(true))).toOption must
       equal(
         QC.inj(Map(RootR, BoolLit(true))).embed.some)
    }

    // TODO EJson does not support Data.Set
    "convert a constant set" in {
      QueryFile.convertToQScript(
        LogicalPlan.Constant(Data.Set(List(
          Data.Obj(ListMap("0" -> Data.Int(2))),
          Data.Obj(ListMap("0" -> Data.Int(3))))))).toOption must
      equal(
         RootR.some) // TODO incorrect expectation
    }

    "convert a very simple read" in {
      QueryFile.convertToQScript(lpRead("/foo")).toOption must
      equal(
        // Map(Root, ProjectField(Unit, "foo"))
        QC.inj(Map(RootR, ProjectFieldR(UnitF, StrLit("foo")))).embed.some)
    }

    "convert a simple read" in {
      QueryFile.convertToQScript(lpRead("/some/foo/bar")).toOption must
      equal(
        QC.inj(
          Map(RootR,
            ProjectFieldR(
              ProjectFieldR(
                ProjectFieldR(
                  UnitF,
                  StrLit("some")),
                StrLit("foo")),
              StrLit("bar")))).embed.some)

      // Map(Root, ObjectProject(ObjectProject(ObjectProject((), "some"), "foo"), "bar"))
    }

    "convert a basic invoke" in {
      QueryFile.convertToQScript(math.Add(lpRead("/foo"), lpRead("/bar")).embed).toOption must
      equal(
        QC.inj(
          Map(RootR,
            Free.roll(Add(
              ProjectFieldR(UnitF, StrLit("foo")),
              ProjectFieldR(UnitF, StrLit("bar")))))).embed.some)
    }

    "convert basic join" in {  // TODO normalization
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
      QueryFile.convertToQScript(lp).toOption must equal(
        QC.inj(Map(RootR, ProjectFieldR(UnitF, StrLit("foo")))).embed.some)
    }
  }
}
