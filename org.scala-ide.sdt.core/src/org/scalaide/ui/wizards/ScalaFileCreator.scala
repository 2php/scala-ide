package org.scalaide.ui.wizards

import scala.util.{ Try, Success, Failure }

import org.eclipse.core.resources.IContainer
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IResource
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.Path
import org.scalaide.core.IScalaPlugin
import org.scalaide.core.compiler.IScalaPresentationCompiler.Implicits._
import org.scalaide.core.internal.project.ScalaProject
import org.scalaide.util.internal.Commons
import org.scalaide.util.internal.eclipse.ProjectUtils

import scalariform.lexer._

object ScalaFileCreator {
  val VariableTypeName = "type_name"
  val VariablePackageName = "package_name"

  import scala.reflect.runtime._
  private[this] val st = universe.asInstanceOf[JavaUniverse]

  val ScalaKeywords = st.nme.keywords map (_.toString())
  val JavaKeywords = st.javanme.keywords map (_.toString())
}

trait ScalaFileCreator extends FileCreator {
  import ScalaFileCreator._
  import ProjectUtils._

  private[wizards] type FileExistenceCheck = IContainer => Validation

  override def templateVariables(folder: IContainer, name: String): Map[String, String] =
    generateTemplateVariables(name)

  override def initialPath(res: IResource): String = {
    val srcDirs = sourceDirs(res.getProject())
    generateInitialPath(
      path = res.getFullPath(),
      srcDirs = srcDirs,
      isDirectory = res.getType() == IResource.FOLDER)
  }

  override def validateName(folder: IContainer, name: String): Validation = {
    if (!ScalaProject.isScalaProject(folder.getProject()))
      Invalid("Not a Scala project")
    else
      doValidation(name) match {
        case Left(v) => v
        case Right(f) => f(folder)
      }
  }

  override def create(folder: IContainer, name: String): IFile = {
    val filePath = name.replace('.', '/')
    folder.getFile(new Path(s"$filePath.scala"))
  }

  override def completionEntries(folder: IContainer, name: String): Seq[String] = {
    val ret = projectAsJavaProject(folder.getProject()) map { jp =>
      val root = jp.findPackageFragmentRoot(folder.getFullPath())
      val pkgs = root.getChildren().map(_.getElementName())
      val ignoreCaseMatcher = s"(?i)\\Q$name\\E.*"

      pkgs.filter(_.matches(ignoreCaseMatcher))
    }

    ret.fold(Seq[String]())(identity)
  }

  /**
   * `path` is the path of the element which is selected when the wizard is
   * created. `srcDirs` contains all source folders of the project where `path`
   * is part of. `isDirectory` describes if the last element of `path` references
   * a directory.
   */
  private[wizards] def generateInitialPath(path: IPath, srcDirs: Seq[IPath], isDirectory: Boolean): String = {
    srcDirs.find(_.isPrefixOf(path))
      .map(srcDir => path.removeFirstSegments(srcDir.segmentCount()))
      .map(pkgOrFilePath => if (isDirectory) pkgOrFilePath else pkgOrFilePath.removeLastSegments(1))
      .map(_.segments().mkString("."))
      .map(pkg => if (pkg.isEmpty()) "" else s"$pkg.")
      .getOrElse("")
  }

  private[wizards] def doValidation(name: String): Either[Invalid, FileExistenceCheck] = {
    if (name.isEmpty())
      Left(Invalid("No file path specified"))
    else
      validateFullyQualifiedType(name)
  }

  private[wizards] def validateFullyQualifiedType(fullyQualifiedType: String): Either[Invalid, FileExistenceCheck] = {
    def isValidScalaTypeIdent(inputStr: String) = {

      val str = inputStr.trim()

      val tokenizeResult = Try(ScalaLexer.tokenise(str, forgiveErrors = false))

      tokenizeResult match {
        case Success(tokens) => {

          val conformsToIdentToken = tokens.size == 2 && tokens(0).tokenType.isId

          conformsToIdentToken && !ScalaKeywords.contains(str)
        }
        case Failure(exception) => {
          exception match {
            case e: scalariform.lexer.ScalaLexerException => false
            case e => throw e
          }
        }
      }
    }

    val dotsAfterBackQuote = fullyQualifiedType.dropWhile(_ != '`').contains('.')

    if (dotsAfterBackQuote) {
      Left(Invalid("Dots after a back-quote is not supported for Scala types in the file wizard"))
    } else {

      val parts = Commons.split(fullyQualifiedType, '.')

      if (parts.last.isEmpty)
        Left(Invalid("No type name specified"))
      else {
        def packageIdentCheck =
          parts.init.find(!isValidScalaPackageIdent(_)) map (e => s"'$e' is not a valid package name")

        def typeIdentCheck =
          Seq(parts.last).find(!isValidScalaTypeIdent(_)) map (e => s"'$e' is not a valid type name")

        packageIdentCheck orElse typeIdentCheck match {
          case Some(e) => Left(Invalid(e))
          case _ => Right(checkTypeExists(_, fullyQualifiedType))
        }
      }
    }
  }

  private[wizards] def isValidScalaPackageIdent(str: String): Boolean = {
    val validIdent =
      str.nonEmpty &&
        Character.isJavaIdentifierStart(str.head) &&
        str.tail.forall(Character.isJavaIdentifierPart)

    validIdent && !ScalaKeywords.contains(str) && !JavaKeywords.contains(str)
  }

  private[wizards] def checkTypeExists(folder: IContainer, fullyQualifiedType: String): Validation = {
    val path = fullyQualifiedType.replace('.', '/')
    if (folder.getFile(new Path(s"$path.scala")).exists())
      Invalid("File already exists")
    else {
      val scalaProject = IScalaPlugin().asScalaProject(folder.getProject())
      val typeExists = scalaProject flatMap { scalaProject =>
        scalaProject.presentationCompiler { compiler =>
          compiler.asyncExec {
            compiler.rootMirror.getClassIfDefined(fullyQualifiedType) != compiler.NoSymbol
          }.getOption()
        }.flatten
      } getOrElse false

      if (typeExists)
        Invalid("Type already exists")
      else
        Valid
    }
  }

  private[wizards] def generateTemplateVariables(pkg: String): Map[String, String] = {
    val splitPos = pkg.lastIndexOf('.')
    if (splitPos < 0)
      Map(VariableTypeName -> pkg)
    else
      Map(
        VariablePackageName -> pkg.substring(0, splitPos),
        VariableTypeName -> pkg.substring(splitPos + 1))
  }
}
