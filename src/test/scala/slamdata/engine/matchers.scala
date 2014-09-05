package slamdata.engine

import scala.reflect.ClassTag

import org.specs2.mutable._
import org.specs2.matcher._
import slamdata.engine.analysis._

import scalaz._

trait ValidationMatchers {
  def beSuccess[E, A] = new Matcher[Validation[E, A]] {
    def apply[S <: Validation[E, A]](s: Expectable[S]) = {
      val v = s.value

      result(
        v.fold(_ => false, _ => true), 
        s"$v is success", 
        s"$v is not success",
        s
      )
    }
  }

  def beFailure[E, A]: Matcher[Validation[E, A]] = new Matcher[Validation[E, A]] {
    def apply[S <: Validation[E, A]](s: Expectable[S]) = {
      val v = s.value

      result(
        v.fold(_ => true, _ => false), 
        s"$v is not success",
        s"$v is success",
        s
      )
    }
  }

  def beSuccess[E, A](a: => A) = validationWith[E, A](Success(a))

  def beFailure[E, A](e: => E) = validationWith[E, A](Failure(e))

  def beFailureWithClass[E: ClassTag, A] = new Matcher[ValidationNel[E, A]] {
    def apply[S <: ValidationNel[E, A]](s: Expectable[S]) = {
      val v = s.value

      val requiredType = implicitly[ClassTag[E]].runtimeClass

      val rez = v match { case Failure(NonEmptyList(e)) => e.getClass == requiredType }

      result(rez, "v is a failure of " + requiredType, "v is not a failure of " + requiredType, s)
    }
  }

  private def validationWith[E, A](f: => Validation[E, A]): Matcher[Validation[E, A]] = new Matcher[Validation[E, A]] {
    def apply[S <: Validation[E, A]](s: Expectable[S]) = {
      val v = s.value

      val expected = f

      result(expected == v, s"$v is $expected", s"$v is not $expected", s)
    }
  }
}

object ValidationMatchers extends ValidationMatchers

trait DisjunctionMatchers {
  def beAnyLeftDisj[A, B]: Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value

      result(v.fold(_ => true, _ => false), s"$v is left", s"$v is not left", s)
    }
  } 

  def beAnyRightDisj[A, B]: Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value

      result(v.fold(_ => false, _ => true), s"$v is right", s"$v is not right", s)
    }
  } 

  def beRightDisj[A, B](p: B => Boolean)(implicit sb: Show[B]): Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value
      val vs = v.fold(a => a.toString(), b => sb.show(b))
      
      result(v.fold(_ => false, p), s"$vs is right", s"$vs is not right", s)
    }
  } 

  def beLeftDisj[A, B](p: A => Boolean)(implicit sa: Show[A]): Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value
      val vs = v.fold(a => sa.show(a), b => b.toString)

      result(v.fold(p, _ => true), s"$vs is left", s"$vs is not left", s)
    }
  }

  def beRightDisj[A, B](expected: B)(implicit sb: Show[B]): Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value
      val vs = v.fold(a => a.toString(), b => sb.show(b))
      val exps = sb.show(expected)
      
      result(v.fold(_ => false, _ == expected), s"$vs is right $exps", s"$vs is not right $exps", s)
    }
  } 

  def beLeftDisj[A, B](expected: A)(implicit sa: Show[A]): Matcher[A \/ B] = new Matcher[A \/ B] {
    def apply[S <: A \/ B](s: Expectable[S]) = {
      val v = s.value
      val vs = v.fold(a => sa.show(a), b => b.toString)
      val exps = sa.show(expected)

      result(v.fold(_ == expected, _ => false), s"$vs is left $exps", s"$vs is not left $exps", s)
    }
  }
}

trait TermLogicalPlanMatchers {
  import slamdata.engine.analysis.fixplate._
  import slamdata.engine.fp._
  import slamdata.engine.analysis._

  case class equalToPlan(expected: Term[LogicalPlan])
      extends Matcher[Term[LogicalPlan]] {
    val equal = Equal[Term[LogicalPlan]].equal _
    val showT = Show[Term[LogicalPlan]].show _
    val showD = Show[Diff[LogicalPlan]].show _
    val diff = Diffable[LogicalPlan].diff _

    def apply[S <: Term[LogicalPlan]](s: Expectable[S]) =
      result(
        equal(expected, s.value),
        "\nthe trees are equal\n" + showT(expected),
        "\nthe trees are not equal:\n" + showD(diff(expected, s.value)),
        s)
  }
}
