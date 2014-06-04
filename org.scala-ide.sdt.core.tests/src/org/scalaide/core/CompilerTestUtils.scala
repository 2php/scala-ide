package org.scalaide.core

import org.scalaide.core.testsetup.SDTTestUtils._
import org.scalaide.core.compiler.InteractiveCompilationUnit
import org.scalaide.core.compiler.IScalaPresentationCompiler
import org.scalaide.core.internal.jdt.model.ScalaSourceFile
import org.eclipse.core.resources.IFile
import scala.collection.mutable.ListBuffer

class CompilerTestUtils(unit: ScalaSourceFile) {

  type TreeTest = (IScalaPresentationCompiler#Tree) => Unit

  /** Retrieve the `target` tree from the given source and pass it to the tree test.
   *
   *  The `src` is supposed to contain one or more members named `target*`.
   *
   *  This method reloads `src` in the presentation compiler and waits for the source
   *  to be fully-typechecked, before traversing the tree to find the `target` definition.
   *
   *  For example:
   *
   *  {{{
   *    trait Foo {
   *      def target1: List[Int]
   *      type target2[T] = (T, T, T)
   *  }}}
   *
   *  f will be calleed with both target1 and target2 trees
   */
  def withTargetTrees(src: String)(f: TreeTest) = {
    changeContentOfFile(unit.getResource().asInstanceOf[IFile], src)

    unit.withSourceFile { (srcFile, compiler) =>
      compiler.askReload(unit, srcFile)

      val targets = compiler.askLoadedTyped(srcFile, keepLoaded = false).get match {
        case Left(loadedType) =>
          loadedType.collect {
            case t: compiler.MemberDef if t.name.toString startsWith "target" => t
          }
        case Right(e) =>
          throw e
      }
      targets.foreach(f)
    } getOrElse (throw new NoSuchElementException(s"Could not find target element in $src"))
  }
}