/**
  *  Intel Intrinsics for Lightweight Modular Staging Framework
  *  https://github.com/ivtoskov/lms-intrinsics
  *  Department of Computer Science, ETH Zurich, Switzerland
  *      __                         _         __         _               _
  *     / /____ ___   _____        (_)____   / /_ _____ (_)____   _____ (_)_____ _____
  *    / // __ `__ \ / ___/______ / // __ \ / __// ___// // __ \ / ___// // ___// ___/
  *   / // / / / / /(__  )/_____// // / / // /_ / /   / // / / /(__  )/ // /__ (__  )
  *  /_//_/ /_/ /_//____/       /_//_/ /_/ \__//_/   /_//_/ /_//____//_/ \___//____/
  *
  *  Copyright (C) 2017 Ivaylo Toskov (itoskov@ethz.ch)
  *                     Alen Stojanov (astojanov@inf.ethz.ch)
  *
  *  Licensed under the Apache License, Version 2.0 (the "License");
  *  you may not use this file except in compliance with the License.
  *  You may obtain a copy of the License at
  *
  *  http://www.apache.org/licenses/LICENSE-2.0
  *
  *  Unless required by applicable law or agreed to in writing, software
  *  distributed under the License is distributed on an "AS IS" BASIS,
  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *  See the License for the specific language governing permissions and
  *  limitations under the License.
  */

package ch.ethz.acl.intrinsics

import java.io.{File, FileOutputStream, PrintStream}

import ch.ethz.acl.intrinsics.MicroArchType._
import ch.ethz.acl.passera.unsigned.{UByte, UInt, ULong, UShort}

import scala.language.postfixOps
import scala.xml.{Node, XML}
import scala.lms.common.{ArrayOpsExp, BooleanOpsExp, PrimitiveOpsExp, SeqOpsExp}

protected class IntrinsicsGenerator extends IntrinsicsBase with ArrayOpsExp with SeqOpsExp with PrimitiveOpsExp with BooleanOpsExp {
  implicit def uIntTyp    : Typ[UInt] = manifestTyp
  implicit def uByteTyp   : Typ[UByte] = manifestTyp
  implicit def uShortTyp  : Typ[UShort] = manifestTyp
  implicit def uLongTyp   : Typ[ULong] = manifestTyp
  implicit def anyTyp     : Typ[Any] = manifestTyp

  val rootPath = new File(".").getAbsolutePath
  val srcPath = rootPath + "/src/main/scala/ch/ethz/acl/intrinsics/"
  val resourcePath = rootPath + "/src/main/resources/"
  val xmlFile = resourcePath + "data-3.3.16.xml"
  val nrIntrinsicsPerFile = 175
  var intrinsicsNames = scala.collection.mutable.Set[String]()

  case class Parameter (varName: String, pType: String)

  case class Intrinsic (node: Node,
                        name: String,
                        tech: String,
                        CPUID: List[String],
                        returnType: String,
                        intrinsicType: List[IntrinsicsType.IntrinsicsType],
                        category: List[IntrinsicsCategory.IntrinsicsCategory],
                        performance: Map[MicroArchType, Performance],
                        nonOffsetParams: List[Parameter],
                        offsetParams: List[Parameter],
                        description: String,
                        operation: List[String],
                        header: String
                       ) {

    def getAllTypeStrings = returnType :: offsetParams.map(_.pType)
    def toXML = node.toString()
    def allParams = nonOffsetParams ::: offsetParams

    def mirroringPrefix = {
      if (extractArrayParams.nonEmpty) {
        "iDef@"
      } else {
        ""
      }
    }

    def getDefName = {
      var defName = name.toUpperCase()

      while (defName.charAt(0) == '_') {
        defName = defName.substring(1)
      }

      assert(!defName.equals(name), "Method and class have the same name")
      defName
    }

    def getParamsAsStrings = {
      allParams.map(p => {
        p.varName + ": " + p.pType
      }).mkString(", ")
    }

    def getLMSParams = {
      allParams.map(p => {
        p.varName + ": Exp[" + (if (offsetParams.contains(p)) "U" else remap(p.pType)) + "]"
      }).mkString(", ")
    }

    def getParams = {
      allParams.map(p => p.varName).mkString(", ")
    }

    def getReturnType = {
      val standardRetType = remap(returnType)
      if (standardRetType == "A[T]") "VoidPointer" else standardRetType
    }

    def extractArrayParams = {
      nonOffsetParams.filter(isArrayParam)
    }

    def prettyDescription = {
      Utils.wrap(description, 78)
    }

    def hasVoidPointers = {
      nonOffsetParams.exists(p => remap(p.pType) == "A[T]")
    }

    def hasArrayTypes = {
      extractArrayParams.nonEmpty || getReturnType.startsWith("A[")
    }

    def typeParams = {
      if (hasArrayTypes) {
        if (hasVoidPointers) "[A[_], T:Typ, U:Integral]" else "[A[_], U:Integral]"
      } else {
        ""
      }
    }

    def abstractContainer = {
      if (hasArrayTypes) {
        "(implicit val cont: Container[A])"
      } else {
        ""
      }
    }

    def abstractNonValContainer = {
      if (hasArrayTypes) {
        "(implicit cont: Container[A])"
      } else {
        ""
      }
    }

    def genMirror(varName: String) = {
      if (extractArrayParams.nonEmpty) {
        s"iDef.cont.applyTransformer($varName, f)"
      } else {
        s"f($varName)"
      }
    }

    def optionallyAddImplContMirror = {
      if (hasArrayTypes) {
        if (hasVoidPointers) "(iDef.voidType, iDef.integralType, iDef.cont)" else "(iDef.integralType, iDef.cont)"
      } else {
        ""
      }
    }

    def addImplCont() = {
      if (hasArrayTypes) {
        if (hasVoidPointers) "(typ[T], implicitly[Integral[U]], cont)" else "(implicitly[Integral[U]], cont)"
      } else {
        ""
      }
    }

    def addSuperClass() = {
      if (hasVoidPointers)
        s"VoidPointerIntrinsicsDef[T, U, $getReturnType]"
      else if (extractArrayParams.nonEmpty)
        s"PointerIntrinsicsDef[U, $getReturnType]"
      else
        s"IntrinsicsDef[$getReturnType]"
    }
  }

  def isArrayParam(p: Parameter) = {
    val tpe = typeMappings(p.pType)
    tpe.typeArguments.nonEmpty
  }

  def santizeParamName(s: String) = s match {
    case "RoundKey" => "roundKey"
    case "type" => "tpe"
    case "val" => "value"
    case _ => s
  }

  def manifestSimple[T](tp: Typ[T]): String = {
    val name = tp match {
      case _ if tp <:< typ[Unit]    => "Unit"
      case _ if tp <:< typ[Double]  => "Double"
      case _ if tp <:< typ[Float]   => "Float"
      case _ if tp <:< typ[Char]    => "Char"
      case _ if tp <:< typ[Boolean] => "Boolean"
      case _ if tp <:< typ[Long]    => "Long"
      case _ if tp <:< typ[Int]     => "Int"
      case _ if tp <:< typ[Short]   => "Short"
      case _ if tp <:< typ[Byte]    => "Byte"
      case _ if tp.runtimeClass.getSimpleName == "Object" => "Any"
      case _ => tp.runtimeClass.getSimpleName
    }
    tp.typeArguments.isEmpty match {
      case true => name
      case _ =>
        val simpleType = manifestSimple(tp.typeArguments.head)
        "A[" + (if (simpleType == "Any") "T" else simpleType) + "]"
    }
  }

  def remap(tpe: String) = {
    val m = typeMappings(tpe)
    manifestSimple(m)
  }


  val typeMappings = Map (

    /* ============= Enums ============= */

    "_MM_BROADCAST32_ENUM"     -> typ[Int],
    "_MM_BROADCAST64_ENUM"     -> typ[Int],
    "_MM_DOWNCONV_EPI32_ENUM"  -> typ[Int],
    "_MM_DOWNCONV_EPI64_ENUM"  -> typ[Int],
    "_MM_DOWNCONV_PD_ENUM"     -> typ[Int],
    "_MM_DOWNCONV_PS_ENUM"     -> typ[Int],
    "_MM_EXP_ADJ_ENUM"         -> typ[Int],
    "_MM_MANTISSA_NORM_ENUM"   -> typ[Int],
    "_MM_MANTISSA_SIGN_ENUM"   -> typ[Int],
    "_MM_PERM_ENUM"            -> typ[Int],
    "_MM_SWIZZLE_ENUM"         -> typ[Int],
    "_MM_UPCONV_EPI32_ENUM"    -> typ[Int],
    "_MM_UPCONV_EPI64_ENUM"    -> typ[Int],
    "_MM_UPCONV_PD_ENUM"       -> typ[Int],
    "_MM_UPCONV_PS_ENUM"       -> typ[Int],
    "const _MM_UPCONV_PS_ENUM" -> typ[Int],
    "const _MM_CMPINT_ENUM"    -> typ[Int],


    /* ============= Intrinsics ============= */

    "__m64"           -> typ[__m64],
    "__m64*"          -> typ[Array[__m64]],
    "__m64 const*"    -> typ[Array[__m64]],

    "__m128"          -> typ[__m128],
    "__m128 *"        -> typ[Array[__m128]],
    "__m128 const *"  -> typ[Array[__m128]],

    "__m128d"         -> typ[__m128d],
    "__m128d *"       -> typ[Array[__m128d]],
    "__m128d const *" -> typ[Array[__m128d]],

    "__m128i"         -> typ[__m128i],
    "_m128i *"        -> typ[Array[__m128i]],
    "__m128i*"        -> typ[Array[__m128i]],
    "__m128i *"       -> typ[Array[__m128i]],
    "const __m128i*"  -> typ[Array[__m128i]],
    "__m128i const*"  -> typ[Array[__m128i]],

    "__m256"          -> typ[__m256],
    "__m256 *"        -> typ[Array[__m256]],

    "__m256d"         -> typ[__m256d],
    "__m256d *"       -> typ[Array[__m256d]],

    "__m256i"         -> typ[__m256i],
    "__m256i *"       -> typ[Array[__m256i]],
    "__m256i const *" -> typ[Array[__m256i]],
    "__m256i const*"  -> typ[Array[__m256i]],

    "__m512"          -> typ[__m512],
    "_m512"          -> typ[__m512],
    "__m512 *"        -> typ[Array[__m512]],
    "__m512d"         -> typ[__m512d],
    "__m512d *"       -> typ[Array[__m512d]],
    "__m512i"         -> typ[__m512i],
    "_m512i"         -> typ[__m512i],

    "__mmask8"        -> typ[Int],
    "__mmask16"       -> typ[Int],
    "_mmask16"       -> typ[Int],
    "__mmask16 *"     -> typ[Array[Int]],
    "__mmask32"       -> typ[Int],
    "__mmask64"       -> typ[Long],

    /* ============= Char / Byte ============= */

    "unsigned char"      -> typ[UByte],

    "char"               -> typ[Byte],
    "__int8"             -> typ[Byte],
    "char const*"        -> typ[Array[Byte]],
    "char*"              -> typ[Array[Byte]],

    /* =============== Short ================ */

    "unsigned short"     -> typ[UShort],
    "unsigned short*"    -> typ[Array[UShort]],
    "unsigned short *"   -> typ[Array[UShort]],

    "short"              -> typ[Short],
    "__int16"            -> typ[Short],

    /* =============== Integer ================ */

    "unsigned"           -> typ[UInt],
    "unsigned int"       -> typ[UInt],
    "unsigned __int32"   -> typ[UInt],
    "const unsigned int" -> typ[UInt],
    "unsigned int*"      -> typ[Array[UInt]],
    "unsigned int *"     -> typ[Array[UInt]],
    "unsigned __int32*"  -> typ[Array[UInt]],

    "int"                -> typ[Int],
    "size_t"             -> typ[Int],
    "__int32"            -> typ[Int],
    "const int"          -> typ[Int],
    "int*"               -> typ[Array[Int]],
    "__int32*"           -> typ[Array[Int]],
    "int const*"         -> typ[Array[Int]],

    /* ================ Long ================== */

    "unsigned long"      -> typ[ULong],
    "unsigned __int64"   -> typ[ULong],
    "unsigned __int64*"  -> typ[Array[ULong]],
    "unsigned __int64 *" -> typ[Array[ULong]],

    "__int64"            -> typ[Long],
    "long long"          -> typ[Long],
    "__int64*"           -> typ[Array[Long]],
    "__int64 const*"     -> typ[Array[Long]],

    /* ================ Float ================== */

    "float"              -> typ[Float],
    "float*"             -> typ[Array[Float]],
    "float *"            -> typ[Array[Float]],
    "float const *"      -> typ[Array[Float]],
    "float const*"       -> typ[Array[Float]],
    "const float*"       -> typ[Array[Float]],

    /* ================ Double ================= */

    "double"             -> typ[Double],
    "double*"            -> typ[Array[Double]],
    "double *"           -> typ[Array[Double]],
    "double const*"      -> typ[Array[Double]],
    "double const *"     -> typ[Array[Double]],
    "const double*"      -> typ[Array[Double]],


    /* ================ Void ================= */

    "void"               -> typ[Unit],
    "void*"              -> typ[Array[Any]],
    "void *"             -> typ[Array[Any]],
    "void const*"        -> typ[Array[Any]],
    "void const *"       -> typ[Array[Any]],
    "const void *"       -> typ[Array[Any]],
    "const void **"      -> typ[DoubleVoidPointer]
  )


  def indent(s: String, ind: String) = {
    s.trim.lines.map(l => ind + l).mkString("\n")
  }


  def generateISA(isa: String, intrinsics: List[Intrinsic], out: PrintStream, statsOutput: PrintStream, parent: String = "") = {
    if (parent == "") statsOutput.println(s"$isa statistics:\n\n")

    out.println(getPreamble)
    out.println("trait " + isa + " extends IntrinsicsBase {")

    var numberOfPointerArguments = 0
    var pointerArgumentsNames: List[String] = List()

    // Generate code for LMS case classes
    intrinsics foreach { in => {
      if (in.extractArrayParams.size > 1) {
        statsOutput.println(s"Intrinsic ${in.name} has ${in.extractArrayParams.size} pointer arguments")
      }
      if (in.getReturnType == "A[T]") {
        statsOutput.println(s"Intrinsic ${in.name} has void return type")
      }

      val perfs = in.performance.keys.map({ arch =>
        val perf = in.performance(arch)
        arch.toString + " -> Performance(" + perf.latency + ", " + perf.throughput + ")"
      }).mkString(", \n")
      val performanceMap = in.performance.size match {
        case 0 => "Map.empty[MicroArchType, Performance]"
        case _ => "Map (\n" + indent(perfs, "      ") + "\n    )"
      }

      out.println("  /**")
      out.println(indent(in.prettyDescription, "   * "))
      out.println(indent(in.getParamsAsStrings, "   * "))
      out.println("   */")

      out.println(
        s"""  case class ${in.getDefName}${in.typeParams}(${in.getLMSParams})${in.abstractContainer} extends ${in.addSuperClass()} {
            |    val category = List(${ in.category.map(c => "IntrinsicsCategory." + c.toString).mkString(", ") })
            |    val intrinsicType = List(${ in.intrinsicType.map(c => "IntrinsicsType." + c.toString).mkString(", ") })
            |    val performance = $performanceMap
            |    val header = "${in.header}"
            |  }
      """.stripMargin)

      out.println()

    }}

    // Generate code for LMS methods
    intrinsics foreach { in =>
      in.getReturnType match {
        case _ if in.getReturnType == "VoidPointer" || in.getReturnType.startsWith("A[") =>
          out.println(
            s"""  def ${in.name}${in.typeParams}(${in.getLMSParams})${in.abstractNonValContainer}: Exp[${in.getReturnType}] = {
                |    reflectMutable(${in.getDefName}(${in.getParams})${in.addImplCont()})
                |  }
            """.stripMargin)
        case _ if in.category.contains(IntrinsicsCategory.Load) =>
          out.println(
            s"""  def ${in.name}${in.typeParams}(${in.getLMSParams})(implicit cont: Container[A]): Exp[${in.getReturnType}] = {
                |    cont.read(${in.extractArrayParams.map(_.varName).mkString(", ")})(${in.getDefName}(${in.getParams})${in.addImplCont()})
                |  }
            """.stripMargin)
        case _ if in.extractArrayParams.nonEmpty =>
          numberOfPointerArguments = numberOfPointerArguments + 1
          pointerArgumentsNames = pointerArgumentsNames ::: List(in.name)

          val arrays = in.extractArrayParams
          out.println(
            s"""  def ${in.name}${in.typeParams}(${in.getLMSParams})(implicit cont: Container[A]): Exp[${in.getReturnType}] = {
                |    cont.write(${arrays.map(_.varName).mkString(", ")})(${in.getDefName}(${in.getParams})${in.addImplCont()})
                |  }
            """.stripMargin)
        case "Unit" =>
          out.println(
            s"""  def ${in.name}${in.typeParams}(${in.getLMSParams}): Exp[${in.getReturnType}] = {
                |    reflectEffect(${in.getDefName}(${in.getParams}))
                |  }
            """.stripMargin)
        case _ =>
          out.println(
            s"""  def ${in.name}${in.typeParams}(${in.getLMSParams}): Exp[${in.getReturnType}] = {
                |    ${in.getDefName}(${in.getParams})
                |  }
            """.stripMargin)
      }
    }

    if (parent == "") statsOutput.println(s"Number of $isa intrinsics: ${intrinsics.size}")
    statsOutput.println(s"Number of intrinsics with pointer arguments: $numberOfPointerArguments")
    pointerArgumentsNames foreach statsOutput.println

    // Generate code for mirroring
    out.println("  override def mirror[A:Typ](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = (e match {")
    intrinsics foreach { in =>
      out.println(
        s"""    case ${in.mirroringPrefix}${in.getDefName} (${in.getParams}) =>
            |      ${in.name}(${in.allParams.map(p => in.genMirror(p.varName)).mkString(", ")})${in.optionallyAddImplContMirror}""".stripMargin)
    }
    out.println()
    intrinsics foreach { in =>
      out.println(
        s"""    case Reflect(${in.mirroringPrefix}${in.getDefName} (${in.getParams}), u, es) =>
            |      reflectMirrored(Reflect(${in.getDefName} (${in.allParams.map(p => in.genMirror(p.varName)).mkString(", ")})${in.optionallyAddImplContMirror}, mapOver(f,u), f(es)))(mtype(typ[A]), pos)""".stripMargin)
    }

    out.println("    case _ => super.mirror(e, f)")
    out.println("  }).asInstanceOf[Exp[A]] // why??")
    out.println("}")
    out.println()

    // Generate trait for code generation
    out.println(
      s"""trait CGen$isa extends CGenIntrinsics {
          |
          |  val IR: ${if (parent == "") isa else parent}
          |  import IR._
          |
         |  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
       """.stripMargin)

    def paramPlusOffset(parameter: Parameter): String = {
      if (!isArrayParam(parameter)) {
        return "${quote(" + parameter.varName + ")}"
      }

      val cast = s"(${parameter.pType}) "
      val basis = "${quote(" + parameter.varName + ")"
      val offset = " + (if(" + parameter.varName + "Offset == Const(0)) \"\" else \" + \" + quote(" + parameter.varName + "Offset))}"
      cast + s"($basis $offset)"
    }

    intrinsics foreach { in =>
      out.println(s"    case iDef@${in.getDefName}(${in.getParams}) =>")
      out.println("      headers += iDef.header")
      if (in.getReturnType == "Unit")
        out.println("      stream.println(%s)".format(s"""s\"${in.name}(${in.nonOffsetParams.map(paramPlusOffset).mkString(", ")});\""""))
      else
        out.println("      emitValDef(sym, %s)".format(s"""s\"${in.name}(${in.nonOffsetParams.map(paramPlusOffset).mkString(", ")})\""""))

    }

    out.println("    case _ => super.emitNode(sym, rhs)")
    out.println("  }")
    out.println("}")
  }

  def getAttribute(node: Node, attr: String) = {
    val value = node.attribute(attr) match {
      case Some(x) => x.toString()
      case None =>
        println(node)
        throw new RuntimeException(s"Can not find attr $attr")
    }
    if (value.trim().equals("")) {
      println(node)
      throw new RuntimeException(s"attr $attr is empty")
    } else {
      value
    }
  }

  def parseIntrinsics(nodes: List[Node]) = {

    nodes map { node =>
      val name = getAttribute(node, "name")
      val techRaw = getAttribute(node, "tech")
      val returnType = name match {
        case "_MM_TRANSPOSE4_PS" => "void"
        case _ => getAttribute(node, "rettype")
      }
      val CPUID           = (node \ "CPUID").map(_.text).toList
      val intrinsicType   = (node \ "type").map(t => strToIntrisicsType(t.text)).toList
      val category        = (node \ "category").map(c => strToIntrinsicsCategory(c.text)).toList
      val descriptionList = (node \ "description").map(_.text).toList
      val operation       = (node \ "operation").map(_.text).toList
      val headerList      = (node \ "header").map(_.text).toList

      val primitiveParams = (node \ "parameter").toList flatMap { param =>
        val pType   = getAttribute(param, "type")
        pType match {
          case "void" => None
          case _ =>
            val varName = santizeParamName(getAttribute(param, "varname"))
            Some(Parameter(varName, pType))
        }
      } distinct
      val arrayParams = primitiveParams.filter(isArrayParam).map(p => Parameter(p.varName + "Offset", "int"))

      val performance = (node \ "perfdata").toList.flatMap({ perf =>
        val arch = strToMicroArchType(getAttribute(perf, "arch"))
        val lat = perf.attribute("lat") match {
          case Some(latNode) => latNode.text.trim match {
            case "" => None
            case "Varies" => None
            case txt => Some(java.lang.Double.parseDouble(txt))
          }
          case None => None
        }

        val tpt = perf.attribute("tpt") match {
          case Some(tptNode) => tptNode.text.trim match {
            case "" => None
            case "Varies" => None
            case txt => Some(java.lang.Double.parseDouble(txt))
          }
          case None => None
        }
        (lat, tpt) match {
          case (None, None) => None
          case _ => Some(arch -> Performance(lat, tpt))
        }
      }).toMap

      // Cleanup
      val tech = techRaw.replace(".", "").replace("-", "").replace("/", "_")
      val description = descriptionList.headOption.getOrElse("No description available for this intrinsic")
      val header = headerList.head

      Intrinsic (node, name, tech, CPUID, returnType, intrinsicType, category,
        performance, primitiveParams, arrayParams, description, operation, header
      )
    }

  }

  def createISA(name: String, allIntrinsics: List[Intrinsic]) = {
    val intrinsics = allIntrinsics
      .filter(in => in.tech.equals(name) && !intrinsicsNames.contains(in.name))
      .groupBy(_.name)
      .mapValues(_.head)
      .values
      .toList
    intrinsicsNames ++= intrinsics.map(in => in.name)
    if (intrinsics.size < nrIntrinsicsPerFile) {
      val path = srcPath + name + ".scala"
      val output = new PrintStream(new FileOutputStream(path))
      val statsOutputFile = new File(rootPath + "/stats/" + name + ".txt")
      statsOutputFile.createNewFile()
      val statsOutput = new PrintStream(new FileOutputStream(statsOutputFile), false)
      generateISA(name, intrinsics, output, statsOutput)
    } else {
      createComplexISA(name, intrinsics)
    }
  }

  def createComplexISA(name: String, intrinsics: List[Intrinsic]) = {
    val statsOutputFile = new File(rootPath + "/stats/" + name + ".txt")
    statsOutputFile.createNewFile()
    val statsOutput = new PrintStream(new FileOutputStream(statsOutputFile), false)
    statsOutput.println(s"$name statistics:\n\n")
    statsOutput.println(s"Number of $name intrinsics: ${intrinsics.size}")

    val subFiles = intrinsics.grouped(nrIntrinsicsPerFile).toList.zipWithIndex
    subFiles foreach {case (subIntrinsics, index) =>
      val newName = name + "0" + index
      val path = srcPath + newName + ".scala"
      val output = new PrintStream(new FileOutputStream(path))
      generateISA(newName, subIntrinsics, output, statsOutput, name)
    }
    val path = srcPath + name + ".scala"
    val out = new PrintStream(new FileOutputStream(path))
    out.println(getLogo)
    out.println("package ch.ethz.acl.intrinsics")
    out.println()
    out.print(s"trait $name extends IntrinsicsBase")
    subFiles foreach {case (_, index) => out.print(" with " + name + "0" + index)}
    val shouldAdd512KNC = (name == "AVX512" || name == "KNC") && new java.io.File(srcPath + "AVX512_KNC.scala").exists
    if (shouldAdd512KNC) {
      out.print(" with AVX512_KNC")
    }
    out.println("\n")

    out.print(s"trait CGen$name extends CGenIntrinsics")
    subFiles foreach {case (_, index) => out.print(" with CGen" + name + "0" + index)}
    if (shouldAdd512KNC) {
      out.print(" with CGenAVX512_KNC")
    }
    out.println(" {")
    out.println(s"  val IR: $name")
    out.println("}\n")
  }

  def generate (): Unit = {
    val xml: Node = XML.loadFile(xmlFile)
    val nodes = (xml \\ "intrinsic").toList

    val intrinsics = parseIntrinsics(nodes)
        createISA("MMX", intrinsics)

        createISA("SSE", intrinsics)
        createISA("SSE2", intrinsics)
        createISA("SSE3", intrinsics)
        createISA("SSSE3", intrinsics)
        createISA("SSE41", intrinsics)
        createISA("SSE42", intrinsics)

        createISA("AVX", intrinsics)
        createISA("AVX2", intrinsics)

        createISA("AVX512_KNC", intrinsics)
        createISA("AVX512", intrinsics)
        createISA("FMA", intrinsics)
        createISA("KNC", intrinsics)
        createISA("SVML", intrinsics)

        createISA("Other", intrinsics)
  }

  def strToMicroArchType(s: String): MicroArchType = s match {
    case "Haswell"      => Haswell
    case "Ivy Bridge"   => IvyBridge
    case "Nehalem"      => Nehalem
    case "Sandy Bridge" => SandyBridge
    case "Westmere"     => Westmere
  }

  def strToIntrisicsType(s: String): IntrinsicsType.IntrinsicsType = s match {
    case "Floating Point" => IntrinsicsType.FloatingPoint
    case "Integer" => IntrinsicsType.Integer
    case "Mask" => IntrinsicsType.Mask
  }

  def strToIntrinsicsCategory(s: String): IntrinsicsCategory.IntrinsicsCategory = {
    import IntrinsicsCategory._
    s match {
      case "Application-Targeted" => ApplicationTargeted
      case "Arithmetic" => Arithmetic
      case "Bit Manipulation" => BitManipulation
      case "Cast" => Cast
      case "Compare" => Compare
      case "Convert" => Convert
      case "Cryptography" => Cryptography
      case "Elementary Math Functions" => ElementaryMathFunctions
      case "General Support" => GeneralSupport
      case "Load" => Load
      case "Logical" => Logical
      case "Mask" => Mask
      case "Miscellaneous" => Miscellaneous
      case "Move" => Move
      case "OS-Targeted" => OSTargeted
      case "Probability/Statistics" => ProbabilityStatistics
      case "Random" => Random
      case "Set" => IntrinsicsSet
      case "Shift" => Shift
      case "Special Math Functions" => SpecialMathFunctions
      case "Store" => Store
      case "String Compare" => StringCompare
      case "Swizzle" => Swizzle
      case "Trigonometry" => Trigonometry
      case _ => throw new RuntimeException("Intrinsics category unknown")
    }
  }

  def getPreamble = {
    getLogo + """
      |package ch.ethz.acl.intrinsics
      |
      |import ch.ethz.acl.intrinsics.MicroArchType._
      |import ch.ethz.acl.passera.unsigned.{UByte, UInt, ULong, UShort}
      |
      |import scala.reflect.SourceContext
      |import scala.language.higherKinds
      |
    """.stripMargin
  }

  def getLogo = {
    """/**
      |  *  Intel Intrinsics for Lightweight Modular Staging Framework
      |  *  https://github.com/ivtoskov/lms-intrinsics
      |  *  Department of Computer Science, ETH Zurich, Switzerland
      |  *      __                         _         __         _               _
      |  *     / /____ ___   _____        (_)____   / /_ _____ (_)____   _____ (_)_____ _____
      |  *    / // __ `__ \ / ___/______ / // __ \ / __// ___// // __ \ / ___// // ___// ___/
      |  *   / // / / / / /(__  )/_____// // / / // /_ / /   / // / / /(__  )/ // /__ (__  )
      |  *  /_//_/ /_/ /_//____/       /_//_/ /_/ \__//_/   /_//_/ /_//____//_/ \___//____/
      |  *
      |  *  Copyright (C) 2017 Ivaylo Toskov (itoskov@ethz.ch)
      |  *                     Alen Stojanov (astojanov@inf.ethz.ch)
      |  *
      |  *  Licensed under the Apache License, Version 2.0 (the "License");
      |  *  you may not use this file except in compliance with the License.
      |  *  You may obtain a copy of the License at
      |  *
      |  *  http://www.apache.org/licenses/LICENSE-2.0
      |  *
      |  *  Unless required by applicable law or agreed to in writing, software
      |  *  distributed under the License is distributed on an "AS IS" BASIS,
      |  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
      |  *  See the License for the specific language governing permissions and
      |  *  limitations under the License.
      |  */
    """.stripMargin
  }

}
