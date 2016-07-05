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
import quasar.fp._
import quasar.physical.mongodbq.expression._
import quasar.physical.mongodbq.IdHandling._
import quasar.physical.mongodbq.Workflow._

import matryoshka._
import scalaz._, Scalaz._

package object workflowtask {
  type WorkflowTask = Fix[WorkflowTaskF]

  type Pipeline = List[PipelineOp]

  // TODO: This should work for any WorkflowF that contains $Project – data
  //       types a la carte.
  val simplifyProject: PipelineF ~> PipelineF =
    new (PipelineF ~> PipelineF) {
      def apply[α](op: PipelineF[α]) = op match {
        case $Project(src, Reshape(cont), id) =>
          $Project(src,
            Reshape(cont.map {
              case (k, \/-($var(DocField(v)))) if k == v => k -> $include().right
              case x                                     => x
            }),
            id)
        case _ => op
      }
    }

  val normalize: WorkflowTaskF ~> WorkflowTaskF =
    new (WorkflowTaskF ~> WorkflowTaskF) {
      def apply[α](wt: WorkflowTaskF[α]) = wt match {
        case PipelineTaskF(src, pipeline) =>
          PipelineTaskF(src, pipeline.map(simplifyProject(_)))
        case x => x
      }
    }

  /** Run once a task is known to be completely built. */
  def finish(base: DocVar, task: WorkflowTask):
      (DocVar, WorkflowTask) = task match {
    case PipelineTask(src, pipeline) =>
      // possibly toss duplicate `_id`s created by `Unwind`s
      val uwIdx = pipeline.lastIndexWhere {
        case $Unwind(_, _) => true;
        case _ => false
      }
      // we’re fine if there’s no `Unwind`, or some existing op fixes the `_id`s
      if (uwIdx == -1 ||
        pipeline.indexWhere(
          { case $Group(_, _, _)           => true
            case $Project(_, _, ExcludeId) => true
            case _                         => false
          },
          uwIdx) != -1)
        (base, task)
      else shape(pipeline) match {
        case Some(names) =>
          (DocVar.ROOT(),
            PipelineTask(
              src,
              pipeline :+
              $Project((),
                Reshape(names.map(_ -> $include().right).toListMap),
                ExcludeId)))

        case None =>
          (Workflow.ExprVar,
            PipelineTask(
              src,
              pipeline :+
                $Project((),
                  Reshape(ListMap(Workflow.ExprName -> $var(base).right)),
                  ExcludeId)))
      }
    case _ => (base, task)
  }

  private def shape(p: Pipeline): Option[List[BsonField.Name]] = {
    def src = shape(p.dropRight(1))

    p.lastOption.flatMap(_ match {
      case op: ShapePreservingF[_]                 => src

      case $Project((), Reshape(shape), _)         => Some(shape.keys.toList)
      case $Group((), Grouped(shape), _)           => Some(shape.keys.toList)
      case $Unwind((), _)                          => src
      case $Redact((), _)                          => None
      case $GeoNear((), _, _, _, _, _, _, _, _, _) => src.map(_ :+ BsonField.Name("dist"))
    })
  }
}
