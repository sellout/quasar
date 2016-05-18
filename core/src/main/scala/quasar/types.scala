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

package quasar

import quasar.Predef._
import quasar.fp._
import SemanticError.{TypeError, MissingField, MissingIndex}

import scala.Any

import argonaut._, Argonaut._
import scalaz._, Scalaz._, NonEmptyList.nels, Validation.{success, failureNel}

sealed trait Type { self =>
  import Type._

  final def ⨯ (that: Type): Type =
    (this, that) match {
      case (t1, t2) if t1.contains(t2) => t2
      case (t1, t2) if t2.contains(t1) => t1
      case (Obj(m1, u1), Obj(m2, u2)) =>
        Obj(m1.unionWith(m2)(_ ⨯ _),
          Apply[Option].lift2((t1: Type, t2: Type) => t1 ⨯ t2)(u1, u2))
      case (FlexArr(min1, max1, t1), FlexArr(min2, max2, t2)) =>
        FlexArr(min1 + min2, (max1 |@| max2)(_ + _), t1 ⨿ t2)
      case (_, _)                     => Bottom
    }

  final def lub: Type = mapUp(self) {
    case x @ Coproduct(_, _) => x.flatten.reduce(Type.lub)
  }

  final def glb: Type = mapUp(self) {
    case x @ Coproduct(_, _) => x.flatten.reduce(Type.glb)
  }

  final def ⨿ (that: Type): Type =
    if (this == that) this else Coproduct(this, that)

  final def contains(that: Type): Boolean =
    typecheck(self, that).fold(κ(false), κ(true))

  final def objectType: Option[Type] = this match {
    case Const(value) => value.dataType.objectType
    case Obj(value, uk) =>
      Some((uk.toList ++ value.toList.map(_._2)).concatenate(TypeOrMonoid))
    case x @ Coproduct(_, _) =>
      x.flatten.toList.map(_.objectType).sequence.map(_.concatenate(TypeOrMonoid))
    case _ => None
  }

  final def objectLike: Boolean = this match {
    case Const(value)        => value.dataType.objectLike
    case Obj(_, _)           => true
    case x @ Coproduct(_, _) => x.flatten.toList.forall(_.objectLike)
    case _                   => false
  }

  final def arrayType: Option[Type] = this match {
    case Const(value) => value.dataType.arrayType
    case Arr(value) => Some(value.concatenate(TypeOrMonoid))
    case FlexArr(_, _, value) => Some(value)
    case x @ Coproduct(_, _) =>
      x.flatten.toList.map(_.arrayType).sequenceU.map(_.concatenate(TypeLubMonoid))
    case _ => None
  }

  final def arrayLike: Boolean = this match {
    case Const(value)        => value.dataType.arrayLike
    case Arr(_)              => true
    case FlexArr(_, _, _)    => true
    case x @ Coproduct(_, _) => x.flatten.toList.forall(_.arrayLike)
    case _                   => false
  }

  final def arrayMinLength: Option[Int] = this match {
    case Const(Data.Arr(value)) => Some(value.length)
    case Arr(value)             => Some(value.length)
    case FlexArr(minLen, _, _)  => Some(minLen)
    case x @ Coproduct(_, _) =>
      x.flatten.toList.foldLeft[Option[Int]](None)((a, n) =>
        (a |@| n.arrayMinLength)(_ min _))
    case _ => None
  }
  final def arrayMaxLength: Option[Int] = this match {
    case Const(Data.Arr(value)) => Some(value.length)
    case Arr(value)             => Some(value.length)
    case FlexArr(_, maxLen, _)  => maxLen
    case x @ Coproduct(_, _)    =>
      x.flatten.toList.foldLeft[Option[Int]](Some(0))((a, n) =>
        (a |@| n.arrayMaxLength)(_ max _))
    case _ => None
  }

  final def objectField(field: Type): ValidationNel[SemanticError, Type] = {
    if (Type.lub(field, Str) != Str) failureNel(TypeError(Str, field, None))
    else (field, this) match {
      case (_, x @ Coproduct (_, _)) => {
        implicit val or: Monoid[Type] = Type.TypeOrMonoid
        val rez = x.flatten.map(_.objectField(field))
        rez.foldMap(_.getOrElse(Bottom)) match {
          case x if simplify(x) ≟ Bottom => rez.concatenate
          case x                         => success(x)
        }
      }

      case (Str, t) =>
        t.objectType.fold[ValidationNel[SemanticError, Type]](
          failureNel(TypeError(AnyObject, this, None)))(
          success)

      case (Const(Data.Str(field)), Const(Data.Obj(map))) =>
        // TODO: import toSuccess as method on Option (via ToOptionOps)?
        toSuccess(map.get(field).map(Const(_)))(nels(MissingField(field)))

      case (Const(Data.Str(field)), Obj(map, uk)) =>
        map.get(field).fold(
          uk.fold[ValidationNel[SemanticError, Type]](
            failureNel(MissingField(field)))(
            success))(
          success)

      case _ => failureNel(TypeError(AnyObject, this, None))
    }
  }

  final def arrayElem(index: Type): ValidationNel[SemanticError, Type] = {
    if (Type.lub(index, Int) != Int) failureNel(TypeError(Int, index, None))
    else (index, this) match {
      case (Const(Data.Int(index)), Const(Data.Arr(arr))) =>
        arr.lift(index.toInt).map(data => success(Const(data))).getOrElse(failureNel(MissingIndex(index.toInt)))

      case (_, x @ Coproduct(_, _)) =>
        implicit val lub: Monoid[Type] = Type.TypeLubMonoid
        x.flatten.toList.foldMap(_.arrayElem(index))

      case (Int, t) =>
        t.arrayType.fold[ValidationNel[SemanticError, Type]](
          failureNel(TypeError(AnyArray, this, None)))(
          success)

      case (Const(Data.Int(index)), FlexArr(min, max, value)) =>
        lazy val succ =
          success(value)
        max.fold[ValidationNel[SemanticError, Type]](
          succ)(
          max => if (index < max) succ else failureNel(MissingIndex(index.toInt)))

      case (Const(Data.Int(index)), Arr(value)) =>
        if (index < value.length)
          success(value(index.toInt))
        else failureNel(MissingIndex(index.toInt))

      case _ => failureNel(TypeError(AnyArray, this, None))
    }
  }
}

trait TypeInstances {
  import Type._

  val TypeOrMonoid = new Monoid[Type] {
    def zero = Type.Bottom

    def append(v1: Type, v2: => Type) = (v1, v2) match {
      case (Type.Bottom, that) => that
      case (this0, Type.Bottom) => this0
      case _ => v1 ⨿ v2
    }
  }

  val TypeAndMonoid = new Monoid[Type] {
    def zero = Type.Top

    def append(v1: Type, v2: => Type) = (v1, v2) match {
      case (Type.Top, that) => that
      case (this0, Type.Top) => this0
      case _ => v1 ⨯ v2
    }
  }

  val TypeGlbMonoid = new Monoid[Type] {
    def zero = Type.Top
    def append(f1: Type, f2: => Type) = Type.glb(f1, f2)
  }

  val TypeLubMonoid = new Monoid[Type] {
    def zero = Type.Bottom
    def append(f1: Type, f2: => Type) = Type.lub(f1, f2)
  }

  implicit val TypeRenderTree: RenderTree[Type] =
    RenderTree.fromToString[Type]("Type")

  implicit val typeEncodeJson: EncodeJson[Type] =
    EncodeJson {
      case Top =>
        jString("Top")
      case Bottom =>
        jString("Bottom")
      case Const(d) =>
        Json("Const" -> DataCodec.Precise.encode(d).getOrElse(jNull))
      case Null =>
        jString("Null")
      case Str =>
        jString("Str")
      case Int =>
        jString("Int")
      case Dec =>
        jString("Dec")
      case Bool =>
        jString("Bool")
      case Binary =>
        jString("Binary")
      case Timestamp =>
        jString("Timestamp")
      case Date =>
        jString("Date")
      case Time =>
        jString("Time")
      case Interval =>
        jString("Interval")
      case Id =>
        jString("Id")
      case Arr(types) =>
        Json("Array" := types)
      case FlexArr(min, max, mbrs) =>
        val flexarr =
          ("minSize" :=  min) ->:
          ("maxSize" :?= max) ->?:
          ("members" :=  mbrs) ->:
          jEmptyObject
        Json("FlexArr" -> flexarr)
      case Obj(assocs, unkns) =>
        val obj =
          ("associations" :=  assocs) ->:
          ("unknownKeys"  :?= unkns)  ->?:
          jEmptyObject
        Json("Obj" -> obj)
      case cp @ Coproduct(l, r) =>
        Json("Coproduct" := cp.flatten)
    }
}

object Type extends TypeInstances {
  private def fail[A](expected: Type, actual: Type, message: Option[String]): ValidationNel[TypeError, A] =
    Validation.failure(NonEmptyList(TypeError(expected, actual, message)))

  private def fail[A](expected: Type, actual: Type): ValidationNel[TypeError, A] = fail(expected, actual, None)

  private def fail[A](expected: Type, actual: Type, msg: String): ValidationNel[TypeError, A] = fail(expected, actual, Some(msg))

  private def succeed[A](v: A): ValidationNel[TypeError, A] = Validation.success(v)

  def simplify(tpe: Type): Type = mapUp(tpe) {
    case x @ Coproduct(_, _) => {
      val ts = x.flatten.toList.filter(_ != Bottom)
      if (ts.contains(Top)) Top else Coproduct(ts.distinct)
    }
    case x => x
  }

  def glb(left: Type, right: Type): Type = {
    if (left ≟ right) left
    else if (left contains right) right
    else if (right contains left) left
    else Bottom
  }

  def lub(left: Type, right: Type): Type = (left, right) match {
    case _ if left ≟ right        => left
    case _ if left contains right => left
    case _ if right contains left => right
    case (Const(l), Const(r))     => lub(l.dataType, r.dataType)
    case _                        => Top
  }

  def matchTypes[A](typ: Type)(cases: (Type, A)*): Option[A] =
    cases.foldLeft[Option[A]](None) { case (acc, (t, a)) =>
      acc.orElse(typecheck(t, typ).toOption ∘ κ(a))
    }

  def typecheck(superType: Type, subType: Type):
      ValidationNel[TypeError, Unit] =
    (superType, subType) match {
      case (superType, subType) if (superType ≟ subType) => succeed(())

      case (Top, _)    => succeed(())
      case (_, Bottom) => succeed(())
      case (_, Top)    => fail(superType, subType, "Top is not a subtype of anything")
      case (Bottom, _) => fail(superType, subType, "Bottom is not a supertype of anything")

      case (superType @ Coproduct(_, _), subType @ Coproduct(_, _)) =>
        typecheckCC(superType.flatten, subType.flatten)
      case (Arr(elem1), Arr(elem2)) =>
        if (elem1.length <= elem2.length)
          Zip[List].zipWith(elem1, elem2)(typecheck).concatenate
        else fail(superType, subType, "subtype must be at least as long")
      case (FlexArr(supMin, supMax, superType), Arr(elem2))
          if supMin <= elem2.length =>
        typecheck(superType, elem2.concatenate(TypeOrMonoid))
      case (FlexArr(supMin, supMax, superType), FlexArr(subMin, subMax, subType)) =>
        lazy val tc = typecheck(superType, subType)
        def checkOpt[A](sup: Option[A], comp: (A, A) => Boolean, sub: Option[A], next: => ValidationNel[TypeError, Unit]) =
          sup.fold(
            next)(
            p => sub.fold[ValidationNel[TypeError, Unit]](
              fail(superType, subType))(
              b => if (comp(p, b)) next else fail(superType, subType)))
        lazy val max = checkOpt(supMax, Order[Int].greaterThanOrEqual, subMax, tc)
        checkOpt(Some(supMin), Order[Int].lessThanOrEqual, Some(subMin), max)
      case (Obj(supMap, supUk), Obj(subMap, subUk)) =>
        supMap.toList.foldMap { case (k, v) =>
          subMap.get(k).fold[ValidationNel[TypeError, Unit]](
            fail(superType, subType))(
            typecheck(v, _))
        } +++
          supUk.fold(
            subUk.fold[ValidationNel[TypeError, Unit]](
              if ((subMap -- supMap.keySet).isEmpty) succeed(()) else fail(superType, subType))(
              κ(fail(superType, subType))))(
            p => subUk.fold[ValidationNel[TypeError, Unit]](
              // if (subMap -- supMap.keySet) is empty, fail(superType, subType)
              (subMap -- supMap.keySet).foldMap(typecheck(p, _)))(
              typecheck(p, _)))

      case (superType, subType @ Coproduct(_, _)) =>
        typecheckPC(superType, subType.flatten)

      case (superType @ Coproduct(_, _), subType) =>
        typecheckCP(superType.flatten, subType)

      case (superType, Const(subType)) => typecheck(superType, subType.dataType)

      case _ => fail(superType, subType)
    }

  def children(v: Type): List[Type] = v match {
    case Top => Nil
    case Bottom => Nil
    case Const(value) => value.dataType :: Nil
    case Null => Nil
    case Str => Nil
    case Int => Nil
    case Dec => Nil
    case Bool => Nil
    case Binary => Nil
    case Timestamp => Nil
    case Date => Nil
    case Time => Nil
    case Interval => Nil
    case Id => Nil
    case Arr(value) => value
    case FlexArr(_, _, value) => value :: Nil
    case Obj(map, uk) => uk.toList ++ map.values.toList
    case x @ Coproduct(_, _) => x.flatten.toList
  }

  def foldMap[Z: Monoid](f: Type => Z)(v: Type): Z =
    Monoid[Z].append(f(v), children(v).foldMap(foldMap(f)))

  def mapUp(v: Type)(f: PartialFunction[Type, Type]): Type = {
    val f0 = f.orElse[Type, Type] {
      case x => x
    }

    mapUpM[scalaz.Id.Id](v)(f0)
  }

  def mapUpM[F[_]: Monad](v: Type)(f: Type => F[Type]): F[Type] = {
    def loop(v: Type): F[Type] = v match {
      case Const(value) =>
         for {
          newType  <- f(value.dataType)
          newType2 <- if (newType != value.dataType) Monad[F].point(newType)
                      else f(v)
        } yield newType2

      case FlexArr(min, max, value) => wrap(value, FlexArr(min, max, _))
      case Arr(value)               => value.map(f).sequence.map(Arr)
      case Obj(map, uk)             =>
        ((map ∘ f).sequence |@| uk.map(f).sequence)(Obj)

      case x @ Coproduct(_, _) =>
        for {
          xs <- Traverse[List].sequence(x.flatten.toList.map(loop _))
          v2 <- f(Coproduct(xs))
        } yield v2

      case _ => f(v)
    }

    def wrap(v0: Type, constr: Type => Type) =
      for {
        v1 <- loop(v0)
        v2 <- f(constr(v1))
      } yield v2

    loop(v)
  }

  final case object Top               extends Type
  final case object Bottom            extends Type

  final case class Const(value: Data) extends Type

  final case object Null              extends Type
  final case object Str               extends Type
  final case object Int               extends Type
  final case object Dec               extends Type
  final case object Bool              extends Type
  final case object Binary            extends Type
  final case object Timestamp         extends Type
  final case object Date              extends Type
  final case object Time              extends Type
  final case object Interval          extends Type
  final case object Id                extends Type

  final case class Arr(value: List[Type]) extends Type
  final case class FlexArr(minSize: Int, maxSize: Option[Int], value: Type)
      extends Type

  // NB: `unknowns` represents the type of any values where we don’t know the
  //      keys. None means the Obj is fully known.
  final case class Obj(value: Map[String, Type], unknowns: Option[Type])
      extends Type

  final case class Coproduct(left: Type, right: Type) extends Type {
    def flatten: Vector[Type] = {
      def flatten0(v: Type): Vector[Type] = v match {
        case left ⨿ right => flatten0(left) ++ flatten0(right)
        case x            => Vector(x)
      }

      flatten0(this)
    }

    override def hashCode = flatten.toSet.hashCode()

    override def equals(that: Any) = that match {
      case that @ Coproduct(_, _) =>
        this.flatten.toSet.equals(that.flatten.toSet)
      case _ => false
    }
  }
  object Coproduct {
    def apply(values: Seq[Type]): Type = {
      if (values.isEmpty) Bottom
      else values.tail.foldLeft[Type](values.head)(_ ⨿ _)
    }
  }

  object ⨿ {
    def unapply(obj: Type): Option[(Type, Type)] = obj match {
      case Coproduct(a, b) => (a, b).some
      case _               => None
    }
  }

  private def typecheckPC(expected: Type, actuals: Vector[Type]) =
    actuals.foldMap(typecheck(expected, _))

  private def typecheckCP(expecteds: Vector[Type], actual: Type) =
    expecteds.foldLeft[ValidationNel[TypeError, Unit]](
      fail(Bottom, actual))(
      (acc, expected) => acc ||| typecheck(expected, actual))

  private def typecheckCC(expecteds: Vector[Type], actuals: Vector[Type]) =
    actuals.foldMap(typecheckCP(expecteds, _))

  val AnyArray = FlexArr(0, None, Top)
  val AnyObject = Obj(Map(), Some(Top))
  val Numeric = Int ⨿ Dec
  val Temporal = Timestamp ⨿ Date ⨿ Time
  val Comparable = Numeric ⨿ Interval ⨿ Str ⨿ Temporal ⨿ Bool
  val Syntaxed = Type.Null ⨿ Type.Comparable

  implicit val equal: Equal[Type] = Equal.equal((a, b) => (a, b) match {
    case (Top,       Top)
       | (Bottom,    Bottom)
       | (Null,      Null)
       | (Str,       Str)
       | (Int,       Int)
       | (Dec,       Dec)
       | (Bool,      Bool)
       | (Binary,    Binary)
       | (Timestamp, Timestamp)
       | (Date,      Date)
       | (Time,      Time)
       | (Interval,  Interval)
       | (Id,        Id) =>
      true
    case (Const(a), Const(b)) => a == b
    case (Arr(as), Arr(bs)) => as ≟ bs
    case (FlexArr(min1, max1, t1), FlexArr(min2, max2, t2)) =>
      min1 ≟ min2 && max1 ≟ max2 && t1 ≟ t2
    case (Obj(v1, u1), Obj(v2, u2)) => v1 ≟ v2 && u1 ≟ u2
    case (a @ Coproduct(_, _), b @ Coproduct(_, _)) =>
      a.flatten.toSet == b.flatten.toSet
    case (_, _) => false
  })
}
