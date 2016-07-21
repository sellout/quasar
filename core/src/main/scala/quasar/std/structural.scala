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

package quasar.std

import quasar.Predef._
import quasar._, LogicalPlan._, SemanticError._
import quasar.fp._

import matryoshka._, Recursive.ops._
import scalaz._, Scalaz._, Validation.{success, failure}
import shapeless.{Data => _, :: => _, _}

trait StructuralLib extends Library {
  import Type._

  val MakeObject = BinaryFunc(
    Mapping,
    "MAKE_OBJECT",
    "Makes a singleton object containing a single field",
    AnyObject,
    Func.Input2(Str, Top),
    noSimplification,
    partialTyper[nat._2] {
      case Sized(Const(Data.Str(name)), Const(Data.Set(data))) =>
        Const(Data.Set(data.map(d => Data.Obj(ListMap(name -> d)))))
      case Sized(Const(Data.Str(name)), Const(data)) => Const(Data.Obj(ListMap(name -> data)))
      case Sized(Const(Data.Str(name)), valueType)   => Obj(Map(name -> valueType), None)
      case Sized(_, valueType)                       => Obj(Map(), Some(valueType))
    },
    partialUntyperV[nat._2] {
      case Const(Data.Obj(map)) => map.headOption match {
        case Some((key, value)) => success(Func.Input2(Const(Data.Str(key)), Const(value)))
        case None => failure(NonEmptyList(GenericError("MAKE_OBJECT can’t result in an empty object")))
      }
      case Obj(map, uk) => map.headOption.fold(
        uk.fold[Func.VDomain[nat._2]](
          failure(NonEmptyList(GenericError("MAKE_OBJECT can’t result in an empty object"))))(
          t => success(Func.Input2(Str, t)))) {
        case (key, value) => success(Func.Input2(Const(Data.Str(key)), value))
      }
    })

  val MakeArray = UnaryFunc(
    Mapping,
    "MAKE_ARRAY",
    "Makes a singleton array containing a single element",
    AnyArray,
    Func.Input1(Top),
    noSimplification,
    partialTyper[nat._1] {
      case Sized(Const(Data.Set(data))) =>
        Const(Data.Set(data.map(d => Data.Arr(d :: Nil))))
      case Sized(Const(data))           => Const(Data.Arr(data :: Nil))
      case Sized(valueType)             => Arr(List(valueType))
    },
    partialUntyper[nat._1] {
      case Const(Data.Arr(List(elem))) => Func.Input1(Const(elem))
      case Arr(List(elemType))         => Func.Input1(elemType)
      case FlexArr(_, _, elemType)     => Func.Input1(elemType)
    })

  val ObjectConcat: BinaryFunc = BinaryFunc(
    Mapping,
    "OBJECT_CONCAT",
    "A right-biased merge of two objects into one object",
    AnyObject,
    Func.Input2(AnyObject, AnyObject),
    noSimplification,
    partialTyperV[nat._2] {
      case Sized(Const(Data.Obj(map1)), Const(Data.Obj(map2))) =>
        success(Const(Data.Obj(
          if (map1.isEmpty)      map2
          else if (map2.isEmpty) map1
          else                   map1 ++ map2)))

      case Sized(Const(o1 @ Data.Obj(_)), o2) => ObjectConcat.tpe(Func.Input2(o1.dataType, o2))
      case Sized(o1, Const(o2 @ Data.Obj(_))) => ObjectConcat.tpe(Func.Input2(o1, o2.dataType))

      case Sized(Obj(map1, uk1), Obj(map2, None))      => success(Obj(map1 ++ map2, uk1))
      case Sized(Obj(map1, uk1), Obj(map2, Some(uk2))) =>
        success(Obj(
          map1 ∘ (_ ⨿ uk2) ++ map2,
          Some(uk1.fold(uk2)(_ ⨿ uk2))))
    },
    partialUntyper[nat._2] {
      case x if x.objectLike =>
        val t = Obj(Map(), x.objectType)
        Func.Input2(t, t)
    })

  val ArrayConcat: BinaryFunc = BinaryFunc(
    Mapping,
    "ARRAY_CONCAT",
    "A merge of two arrays into one array",
    AnyArray,
    Func.Input2(AnyArray, AnyArray),
    noSimplification,
    partialTyperV[nat._2] {
      case Sized(Const(Data.Arr(els1)), Const(Data.Arr(els2))) => success(Const(Data.Arr(els1 ++ els2)))
      case Sized(Const(Data.Arr(els1)), a2) if els1.isEmpty    => success(a2)
      case Sized(a1, Const(Data.Arr(els2))) if els2.isEmpty    => success(a1)
      case Sized(Arr(els1), Arr(els2))                         => success(Arr(els1 ++ els2))

      case Sized(Const(a1 @ Data.Arr(_)), a2) => ArrayConcat.tpe(Func.Input2(a1.dataType, a2))
      case Sized(a1, Const(a2 @ Data.Arr(_))) => ArrayConcat.tpe(Func.Input2(a1, a2.dataType))

      case Sized(a1, FlexArr(min2, max2, elem2)) =>
        (a1.arrayMinLength |@| a1.arrayType)((min1, typ1) =>
          success(FlexArr(
            min1 + min2,
            (a1.arrayMaxLength |@| max2)(_ + _),
            Type.lub(typ1, elem2))))
          .getOrElse(failure(NonEmptyList(GenericError(a1.shows + " is not an array."))))

      case Sized(FlexArr(min1, max1, elem1), a2) =>
        (a2.arrayMinLength |@| a2.arrayType)((min2, typ2) =>
          success(FlexArr(
            min1 + min2,
            (max1 |@| a2.arrayMaxLength)(_ + _),
            Type.lub(elem1, typ2))))
          .getOrElse(failure(NonEmptyList(GenericError(a2.shows + " is not an array."))))
    },
    partialUntyperV[nat._2] {
      case x if x.arrayLike =>
        x.arrayType.fold[Func.VDomain[nat._2]](
          failure(NonEmptyList(GenericError("internal error: " + x.shows + " is arrayLike, but no arrayType")))) {
          typ =>
            val t = FlexArr(0, x.arrayMaxLength, typ)
            success(Func.Input2(t, t))
        }
    })

  // NB: Used only during type-checking, and then compiled into either (string) Concat or ArrayConcat.
  val ConcatOp = BinaryFunc(
    Mapping,
    "(||)",
    "A merge of two arrays/strings.",
    AnyArray ⨿ Str,
    Func.Input2(AnyArray ⨿ Str, AnyArray ⨿ Str),
    noSimplification,
    partialTyperV[nat._2] {
      case Sized(t1, t2) if t1.arrayLike && t2.contains(AnyArray ⨿ Str) => ArrayConcat.tpe(Func.Input2(t1, FlexArr(0, None, Top)))
      case Sized(t1, t2) if t1.contains(AnyArray ⨿ Str) && t2.arrayLike => ArrayConcat.tpe(Func.Input2(FlexArr(0, None, Top), t2))
      case Sized(t1, t2) if t1.arrayLike && t2.arrayLike                => ArrayConcat.tpe(Func.Input2(t1, t2))

      case Sized(Const(Data.Str(str1)), Const(Data.Str(str2)))              => success(Const(Data.Str(str1 ++ str2)))
      case Sized(t1, t2) if Str.contains(t1) && t2.contains(AnyArray ⨿ Str) => success(Type.Str)
      case Sized(t1, t2) if t1.contains(AnyArray ⨿ Str) && Str.contains(t2) => success(Type.Str)
      case Sized(t1, t2) if Str.contains(t1) && Str.contains(t2)            => StringLib.Concat.tpe(Func.Input2(t1, t2))

      case Sized(t1, t2) if t1 == t2 => success(t1)

      case Sized(t1, t2) if Str.contains(t1) && t2.arrayLike => failure(NonEmptyList(GenericError("cannot concat string with array")))
      case Sized(t1, t2) if t1.arrayLike && Str.contains(t2) => failure(NonEmptyList(GenericError("cannot concat array with string")))
    },
    partialUntyperV[nat._2] {
      case x if x.contains(AnyArray ⨿ Str) => success(Func.Input2(AnyArray ⨿ Str, AnyArray ⨿ Str))
      case x if x.arrayLike                => ArrayConcat.untpe(x)
      case x if x.contains(Type.Str)       => StringLib.Concat.untpe(x)
    })

  val ObjectProject = BinaryFunc(
    Mapping,
    "({})",
    "Extracts a specified field of an object",
    Top,
    Func.Input2(AnyObject, Str),
    new Func.Simplifier {
      def apply[T[_[_]]: Recursive: Corecursive](orig: LogicalPlan[T[LogicalPlan]]) = orig match {
        case InvokeFUnapply(_, Sized(Embed(MakeObjectN(obj)), Embed(field))) =>
          obj.map(_.leftMap(_.project)).toListMap.get(field).map(_.project)
        case _ => None
      }
    },
    partialTyperV[nat._2] {
      case Sized(v1, v2) => v1.objectField(v2)
    },
    basicUntyper)

  val ArrayProject = BinaryFunc(
    Mapping,
    "([])",
    "Extracts a specified index of an array",
    Top,
    Func.Input2(AnyArray, Int),
    noSimplification,
    partialTyperV[nat._2] {
      case Sized(v1, Const(Data.Set(elems))) =>
        elems.traverse(e => v1.arrayElem(Const(e))).map(Coproduct(_))
      case Sized(v1, v2) => v1.arrayElem(v2)
    },
    basicUntyper)

  val DeleteField: BinaryFunc = BinaryFunc(
    Mapping,
    "DELETE_FIELD",
    "Deletes a specified field from an object",
    AnyObject,
    Func.Input2(AnyObject, Str),
    noSimplification,
    partialTyper[nat._2] {
      case Sized(Const(Data.Obj(map)), Const(Data.Str(key))) =>
        Const(Data.Obj(map - key))
      case Sized(Obj(map, uk), Const(Data.Str(key))) => Obj(map - key, uk)
      case Sized(v1, _) => Obj(Map(), v1.objectType)
    },
    partialUntyperV[nat._2] {
      case Const(o @ Data.Obj(map)) => DeleteField.untpe(o.dataType)
      case Obj(map, _)              => success(Func.Input2(Obj(map, Some(Top)), Str))
    })

  val FlattenMap = UnaryFunc(
    Expansion,
    "FLATTEN_MAP",
    "Zooms in on the values of a map, extending the current dimension with the keys",
    Top,
    Func.Input1(AnyObject),
    noSimplification,
    partialTyperV[nat._1] {
      case Sized(Const(Data.Obj(map))) =>
        success(Const(Data.Set(map.values.toList)))
      case Sized(x) if x.objectLike =>
        x.objectType.fold[Func.VCodomain](
          failure(NonEmptyList(GenericError("internal error: objectLike, but no objectType"))))(
          success)
    },
    untyper[nat._1](tpe => success(Func.Input1(Obj(Map(), Some(tpe))))))

  val FlattenArray = UnaryFunc(
    Expansion,
    "FLATTEN_ARRAY",
    "Zooms in on the elements of an array, extending the current dimension with the indices",
    Top,
    Func.Input1(AnyArray),
    noSimplification,
    partialTyperV[nat._1] {
      case Sized(Const(Data.Arr(elems))) => success(Const(Data.Set(elems)))
      case Sized(x) if x.arrayLike       =>
        x.arrayType.fold[Func.VCodomain](
          failure(NonEmptyList(GenericError("internal error: arrayLike, but no arrayType"))))(
          success)
    },
    untyper[nat._1](tpe => success(Func.Input1(FlexArr(0, None, tpe)))))

  val FlattenMapKeys = UnaryFunc(
    Expansion,
    "{*:}",
    "Zooms in on the keys of a map, also extending the current dimension with the keys",
    Top,
    Func.Input1(AnyObject),
    noSimplification,
    partialTyper[nat._1] {
      case Sized(Const(Data.Obj(map))) => Const(Data.Set(map.keys.toList ∘ Data.Str))
      case Sized(x) if x.objectLike    => Str
    },
    untyper[nat._1](tpe => success(Func.Input1(Obj(Map(), Some(Top))))))

  val FlattenArrayIndices = UnaryFunc(
    Expansion,
    "[*:]",
    "Zooms in on the indices of an array, also extending the current dimension with the indices",
    Int,
    Func.Input1(AnyArray),
    noSimplification,
    partialTyper[nat._1] {
      case Sized(x) if x.arrayLike => Int
    },
    partialUntyper[nat._1] {
      case Int => Func.Input1(FlexArr(0, None, Top))
    })

  val ShiftMap = UnaryFunc(
    Expansion,
    "SHIFT_MAP",
    "Zooms in on the values of a map, adding the keys as a new dimension",
    Top,
    Func.Input1(AnyObject),
    noSimplification,
    partialTyperV[nat._1] {
      case Sized(x) if x.objectLike =>
        x.objectType.fold[ValidationNel[SemanticError, Type]](
          failure(NonEmptyList(GenericError("internal error: objectLike, but no objectType"))))(
          success)
    },
    untyper[nat._1](tpe => success(Func.Input1(Obj(Map(), Some(tpe))))))

  val ShiftArray = UnaryFunc(
    Expansion,
    "SHIFT_ARRAY",
    "Zooms in on the elements of an array, adding the indices as a new dimension",
    Top,
    Func.Input1(AnyArray),
    new Func.Simplifier {
      def apply[T[_[_]]: Recursive: Corecursive](orig: LogicalPlan[T[LogicalPlan]]) = orig match {
        case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply(UnshiftArray, Sized(Embed(set)))))) => set.some
        case _                                                                                => None
      }
    },
    partialTyperV[nat._1] {
      case Sized(Const(Data.Arr(elems))) => success(Const(Data.Set(elems)))
      case Sized(x) if x.arrayLike =>
        x.arrayType.fold[ValidationNel[SemanticError, Type]](
          failure(NonEmptyList(GenericError("internal error: arrayLike, but no arrayType"))))(
          success)
    },
    untyper[nat._1](tpe => success(Func.Input1(FlexArr(0, None, tpe)))))

  val ShiftMapKeys = UnaryFunc(
    Expansion,
    "{_:}",
    "Zooms in on the keys of a map, also adding the keys as a new dimension",
    Top,
    Func.Input1(AnyObject),
    noSimplification,
    partialTyper[nat._1] {
      case Sized(x) if x.objectLike => Str
    },
    untyper[nat._1](tpe => success(Func.Input1(Obj(Map(), Some(Top))))))

  val ShiftArrayIndices = UnaryFunc(
    Expansion,
    "[_:]",
    "Zooms in on the indices of an array, also adding the keys as a new dimension",
    Int,
    Func.Input1(AnyArray),
    noSimplification,
    partialTyper[nat._1] {
      case Sized(x) if x.arrayLike => Int
    },
    partialUntyper[nat._1] {
      case Int => Func.Input1(FlexArr(0, None, Top))
    })

  val UnshiftMap: BinaryFunc = BinaryFunc(
    Reduction,
    "({...})",
    "Puts the value into a map with the key.",
    AnyObject,
    Func.Input2(Top, Top),
    noSimplification,
    // new Func.Simplifier {
    //   def apply[T[_[_]]: Recursive: Corecursive](orig: LogicalPlan[T[LogicalPlan]]) = orig match {
    //     case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply(ShiftMap, Sized(Embed(map)))))) => map.some
    //     case _                                                                            => None
    //   }
    // },
    partialTyper[nat._2] {
      case Sized(_, value) => Obj(Map(), Some(value))
    },
    partialUntyperV[nat._2] {
      case tpe if tpe.objectLike =>
        tpe.objectType.fold[Func.VDomain[nat._2]](
          failure(NonEmptyList(GenericError("internal error: objectLike, but no objectType"))))(
          x => success(Func.Input2(Top, x)))
    })

  val UnshiftArray: UnaryFunc = UnaryFunc(
    Reduction,
    "[...]",
    "Puts the values into an array.",
    AnyArray,
    Func.Input1(Top),
    new Func.Simplifier {
      def apply[T[_[_]]: Recursive: Corecursive](orig: LogicalPlan[T[LogicalPlan]]) = orig match {
        case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply(ShiftArray, Sized(Embed(array)))))) => array.some
        case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply(ShiftArrayIndices, Sized(Embed(ConstantF(Data.Arr(array)))))))) =>
          ConstantF(Data.Arr((0 until array.length).toList ∘ (Data.Int(_)))).some
        case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply( ShiftMap, Sized(Embed(ConstantF(Data.Obj(map)))))))) =>
          ConstantF(Data.Arr(map.values.toList)).some
        case InvokeFUnapply(_, Sized(Embed(InvokeFUnapply( ShiftMapKeys, Sized(Embed(ConstantF(Data.Obj(map)))))))) =>
          ConstantF(Data.Arr(map.keys.toList.map(Data.Str(_)))).some
        case _ => None
      }
    },
    partialTyper[nat._1] {
      // case Sized(Const(Data.Set(vs))) => Const(Data.Arr(vs))
      case Sized(Const(v))            => Const(Data.Arr(List(v)))
      case Sized(tpe)                 => FlexArr(0, None, tpe)
    },
    partialUntyperV[nat._1] {
      case tpe if tpe.arrayLike =>
        tpe.arrayType.fold[Func.VDomain[nat._1]](
          failure(NonEmptyList(GenericError("internal error: arrayLike, but no arrayType"))))(
          x => success(Func.Input1(x)))
    })

  def unaryFunctions: List[GenericFunc[nat._1]] =
    MakeArray :: FlattenMap :: FlattenArray ::
    FlattenMapKeys :: FlattenArrayIndices ::
    ShiftMap :: ShiftArray :: ShiftMapKeys ::
    ShiftArrayIndices :: UnshiftArray :: Nil

  def binaryFunctions: List[GenericFunc[nat._2]] =
    MakeObject :: ObjectConcat :: ArrayConcat ::
    ConcatOp :: ObjectProject :: ArrayProject ::
    DeleteField :: UnshiftMap :: Nil

  def ternaryFunctions: List[GenericFunc[nat._3]] = Nil

  // TODO: fix types and add the VirtualFuncs to the list of functions

  // val MakeObjectN = new VirtualFunc {
  object MakeObjectN {
    // Note: signature does not match VirtualFunc
    def apply[T[_[_]]: Corecursive](args: (T[LogicalPlan], T[LogicalPlan])*): LogicalPlan[T[LogicalPlan]] =
      args.toList match {
        case Nil      => ConstantF(Data.Obj(ListMap()))
        case x :: xs  =>
          xs.foldLeft(MakeObject(x._1, x._2))((a, b) =>
            ObjectConcat(a.embed, MakeObject(b._1, b._2).embed))
      }

    // Note: signature does not match VirtualFunc
    def unapply[T[_[_]]: Recursive](t: LogicalPlan[T[LogicalPlan]]):
        Option[List[(T[LogicalPlan], T[LogicalPlan])]] =
      t match {
        case InvokeFUnapply(MakeObject, Sized(name, expr)) => Some(List((name, expr)))
        case InvokeFUnapply(ObjectConcat, Sized(a, b))     => (unapply(a.project) ⊛ unapply(b.project))(_ ::: _)
        case _                                             => None
      }
  }

  object MakeArrayN {
    def apply[T[_[_]]: Corecursive](args: T[LogicalPlan]*): LogicalPlan[T[LogicalPlan]] =
      args.map(x => MakeArray(x)) match {
        case Nil      => ConstantF(Data.Arr(Nil))
        case t :: Nil => t
        case mas      => mas.reduce((t, ma) => ArrayConcat(t.embed, ma.embed))
      }

    def unapply[T[_[_]]: Recursive](t: T[LogicalPlan]): Option[List[T[LogicalPlan]]] =
      t.project match {
        case InvokeFUnapply(MakeArray, Sized(x))      => Some(x :: Nil)
        case InvokeFUnapply(ArrayConcat, Sized(a, b)) => (unapply(a) ⊛ unapply(b))(_ ::: _)
        case _                                        => None
      }

    object Attr {
      def unapply[A](t: Cofree[LogicalPlan, A]):
          Option[List[Cofree[LogicalPlan, A]]] =
        MakeArrayN.unapply[Cofree[?[_], A]](t)
    }
  }
}

object StructuralLib extends StructuralLib

