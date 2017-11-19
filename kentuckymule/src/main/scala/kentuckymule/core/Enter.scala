package kentuckymule.core

import java.util

import dotty.tools.dotc
import dotc.ast.Trees._
import dotc.core.Contexts.Context
import dotc.core.Names.{Name, TermName, TypeName}
import dotc.{CompilationUnit, ast}
import dotc.core.Decorators._
import dotc.core.StdNames._
import Symbols._
import Types._
import dotc.core.{Decorators, Flags, TypeOps}

/**
  * Creates symbols for declarations and enters them into a symbol table.
  */
class Enter {

  import Enter._
  import ast.untpd._

  val completers: util.Deque[Completer] = new util.ArrayDeque[Completer]()

  def queueCompleter(completer: Completer, pushToTheEnd: Boolean = true): Unit = {
    if (pushToTheEnd)
      completers.add(completer)
    else
      completers.addFirst(completer)
  }

  class ClassSignatureLookupScope(classSym: ClassSymbol, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = classSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      parentScope.lookup(name)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      throw new UnsupportedOperationException("There can't be any imports declared withing class signature")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  class LookupClassTemplateScope(classSym: ClassSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val classFoundSym = classSym.lookup(name)
      if (classFoundSym != NoSymbol)
        return LookedupSymbol(classFoundSym)
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupClassTemplateScope(classSym, imports, parentScope)
    override def enclosingClass: LookupAnswer = LookedupSymbol(classSym)
  }

  class LookupModuleTemplateScope(moduleSym: ModuleSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val foundSym = moduleSym.lookup(name)
      if (foundSym != NoSymbol)
        LookedupSymbol(foundSym)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parentScope.lookup(name)
        }
      }
    }
    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupModuleTemplateScope(moduleSym, imports, parentScope)

    override def enclosingClass: LookupAnswer = LookedupSymbol(moduleSym.clsSym)
  }

  class LookupDefDefScope(defSym: DefDefSymbol, imports: ImportsLookupScope, parentScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (name.isTypeName) {
        val tParamFoundSym = defSym.typeParams.lookup(name)
        if (tParamFoundSym != NoSymbol)
          return LookedupSymbol(tParamFoundSym)
      }
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => parentScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupDefDefScope(defSym, imports, parentScope)

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  object RootPackageLookupScope extends LookupScope {
    private var scalaPkg: Symbol = _
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      // TODO: lookup in the scala package performed in the root package
      // lookup scope is a hack. We should either introduce as a separate scope
      // or add an implicit import as it's specified in the spec
      if (scalaPkg == null) {
        // fun fact: if we don't cache the scala pkg symbol but perform the lookup
        // below every time in this method, we loose 100 ops/s in the
        // BenchmarkScalap.completeMemberSigs
        scalaPkg = context.definitions.rootPackage.lookup(nme.scala_)
      }
      if (scalaPkg != NoSymbol) {
        if (!scalaPkg.isComplete)
          return IncompleteDependency(scalaPkg)
        val sym = scalaPkg.lookup(name)
        if (sym == NoSymbol)
          NotFound
        else
          return LookedupSymbol(sym)
      }
      locally {
        val javaPkg = context.definitions.rootPackage.lookup(nme.java)
        if (javaPkg != NoSymbol) {
          if (!javaPkg.isComplete)
            return IncompleteDependency(javaPkg)
          val javaLangPkg = javaPkg.lookup(nme.lang)
          if (!javaLangPkg.isComplete)
            return IncompleteDependency(javaLangPkg)
          val sym = javaLangPkg.lookup(name)
          if (sym == NoSymbol)
            NotFound
          else
            return LookedupSymbol(sym)
        }
      }
      val sym = context.definitions.rootPackage.lookup(name)
      if (sym != NoSymbol)
        LookedupSymbol(sym)
      else if (name == context.definitions.rootPackage.name)
        LookedupSymbol(context.definitions.rootPackage)
      else
        NotFound
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = NotFound
  }

  class PredefLookupScope(parentScope: LookupScope) extends LookupScope {
    var predefSymbol: Symbol = _
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (predefSymbol == null) {
        val scalaPkg = context.definitions.rootPackage.lookup(nme.scala_)
        if (scalaPkg != NoSymbol) {
          if (!scalaPkg.isComplete)
            return IncompleteDependency(scalaPkg)
          val sym = scalaPkg.lookup(nme.Predef)
          predefSymbol = sym
        } else predefSymbol = NoSymbol
      }

      if (predefSymbol != NoSymbol) {
        if (!predefSymbol.isComplete)
          return IncompleteDependency(predefSymbol)
        val sym = predefSymbol.info.lookup(name)
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
      }
      parentScope.lookup(name)
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      sys.error("unsupported operation")

    override def enclosingClass: LookupAnswer = parentScope.enclosingClass
  }

  class LookupCompilationUnitScope(imports: ImportsLookupScope, pkgLookupScope: LookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val impFoundSym = imports.lookup(name)
      impFoundSym match {
        case _: LookedupSymbol | _: IncompleteDependency => impFoundSym
        case _ => pkgLookupScope.lookup(name)
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new LookupCompilationUnitScope(imports, pkgLookupScope)

    override def enclosingClass: LookupAnswer = NotFound
  }

  def enterCompilationUnit(unit: CompilationUnit)(implicit context: Context): Unit = {
    val toplevelScope = {
      // TODO: hack, check for declaration of Predef object
      if (unit.source.file.name != "Predef.scala")
        new PredefLookupScope(RootPackageLookupScope)
      else
        RootPackageLookupScope
    }
    val importsInCompilationUnit = new ImportsCollector(toplevelScope)
    val compilationUnitScope = new LookupCompilationUnitScope(importsInCompilationUnit.snapshot(), toplevelScope)
    val lookupScopeContext = new LookupScopeContext(importsInCompilationUnit, compilationUnitScope)
    try { 
      enterTree(unit.untpdTree, context.definitions.rootPackage, lookupScopeContext)
    } catch {
      case ex: Exception => throw new RuntimeException(s"Error while entering symbols from ${unit.source}", ex)
    }

  }

  class PackageLookupScope(val pkgSym: Symbol, val parent: LookupScope, val imports: ImportsLookupScope) extends LookupScope {
    override def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      val pkgMember = if (!pkgSym.isComplete) {
        return IncompleteDependency(pkgSym)
      } else {
        pkgSym.info.lookup(name)
      }
      if (pkgMember != NoSymbol)
        LookedupSymbol(pkgMember)
      else {
        val ans = imports.lookup(name)
        ans match {
          case _: LookedupSymbol | _: IncompleteDependency => ans
          case _ => parent.lookup(name)
        }
      }
    }

    override def replaceImports(imports: ImportsLookupScope): LookupScope =
      new PackageLookupScope(pkgSym, parent, imports)

    override def enclosingClass: LookupAnswer = NotFound
  }

  private class LookupScopeContext(imports: ImportsCollector, val parentScope: LookupScope) {
    private var cachedSimpleMemberLookupScope: LookupScope = parentScope
    def addImport(imp: Import): Unit = {
      cachedSimpleMemberLookupScope = null
      imports.append(imp)
    }
    def pushPackageLookupScope(pkgSym: PackageSymbol): LookupScopeContext = {
      val pkgLookupScope = new PackageLookupScope(pkgSym, parentScope, imports.snapshot())
      val pkgImports = new ImportsCollector(pkgLookupScope)
      new LookupScopeContext(pkgImports, pkgLookupScope)
    }
    def pushModuleLookupScope(modSym: ModuleSymbol): LookupScopeContext = {
      val moduleLookupScope = new LookupModuleTemplateScope(modSym, imports.snapshot(), parentScope)
      val moduleImports = new ImportsCollector(moduleLookupScope)
      new LookupScopeContext(moduleImports, moduleLookupScope)
    }
    def pushClassSignatureLookupScope(classSymbol: ClassSymbol): LookupScopeContext = {
      val classSignatureLookupScope = new ClassSignatureLookupScope(classSymbol, parentScope)
      new LookupScopeContext(imports, classSignatureLookupScope)
    }
    def pushClassLookupScope(classSym: ClassSymbol): LookupScopeContext = {
      val classLookupScope = new LookupClassTemplateScope(classSym, imports.snapshot(), parentScope)
      // imports collector receives class lookup scope so the following construct is supported
      // class Bar { val y: String = "abc" }
      // class Foo { import x.y; val x: Bar = new Bar }
      // YES! Imports can have forward references in Scala (which is a little bit strange)
      val classImports = new ImportsCollector(classLookupScope)
      new LookupScopeContext(classImports, classLookupScope)
    }

    private def simpleMemberLookupScope(): LookupScope = {
      if (cachedSimpleMemberLookupScope != null)
        cachedSimpleMemberLookupScope
      else {
        cachedSimpleMemberLookupScope = parentScope.replaceImports(imports.snapshot())
        cachedSimpleMemberLookupScope
      }
    }

    def newValDefLookupScope(valDefSymbol: ValDefSymbol): LookupScope = simpleMemberLookupScope()
    def newDefDefLookupScope(defDefSymbol: DefDefSymbol): LookupScope =
      if (defDefSymbol.typeParams.size > 0) {
        new LookupDefDefScope(defDefSymbol, imports.snapshot(), parentScope)
      } else {
        simpleMemberLookupScope()
      }
  }

  private def enterTree(tree: Tree, owner: Symbol, parentLookupScopeContext: LookupScopeContext)(implicit context: Context): Unit = tree match {
    case PackageDef(ident, stats) =>
      val pkgSym = expandQualifiedPackageDeclaration(ident, owner)
      val lookupScopeContext = parentLookupScopeContext.pushPackageLookupScope(pkgSym)
      for (stat <- stats) enterTree(stat, pkgSym, lookupScopeContext)
    case imp: Import =>
      parentLookupScopeContext.addImport(imp)
    case md@ModuleDef(name, tmpl) =>
      import dotty.tools.dotc.core.NameOps._
      val isPackageObject = md.mods is Flags.Package
      val modName = if (isPackageObject) nme.PACKAGE else name
      val modOwner = if (isPackageObject) lookupOrCreatePackage(name, owner) else owner
      val modClsSym = ClassSymbol(modName.moduleClassName, owner)
      val modSym = ModuleSymbol(modName, modClsSym, owner)
      if (isPackageObject) {
        assert(modOwner.isInstanceOf[PackageSymbol], "package object has to be declared inside of a package (enforced by syntax)")
        modOwner.asInstanceOf[PackageSymbol].packageObject = modSym
      }
      val lookupScopeContext = if (isPackageObject) {
        parentLookupScopeContext.
          pushPackageLookupScope(modOwner.asInstanceOf[PackageSymbol]).
          pushModuleLookupScope(modSym)
      } else {
        parentLookupScopeContext.pushModuleLookupScope(modSym)
      }
      modOwner.addChild(modSym)
      locally {
        val completer = new TemplateMemberListCompleter(modClsSym, tmpl, lookupScopeContext.parentScope)
        queueCompleter(completer, pushToTheEnd = !isPackageObject)
        modClsSym.completer = completer
      }
      locally {
        val completer = new ModuleCompleter(modSym)
        queueCompleter(completer, pushToTheEnd = !isPackageObject)
        modSym.completer = completer
      }
      for (stat <- tmpl.body) enterTree(stat, modClsSym, lookupScopeContext)
    // class or trait
    case t@TypeDef(name, tmpl: Template) if t.isClassDef =>
      val classSym = ClassSymbol(name, owner)
      // t.tParams is empty for classes, the type parameters are accessible thorugh its primary constructor
      foreachWithIndex(tmpl.constr.tparams) { (tParam, tParamIndex) =>
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        classSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex, classSym))
      }
      owner.addChild(classSym)
      // surprisingly enough, this conditional lets us save over 100 ops/s for the completeMemberSigs benchmark
      // we get 1415 ops/s if we unconditionally push class signature lookup scope vs 1524 ops/s with the condition
      // below
      val classSignatureLookupScopeContext =
        if (tmpl.constr.tparams.nonEmpty)
          parentLookupScopeContext.pushClassSignatureLookupScope(classSym)
        else
          parentLookupScopeContext

      tmpl.constr.vparamss foreach { vparams =>
        vparams foreach { vparam =>
          // we're entering constructor parameter as a val declaration in a class
          // TODO: these parameters shouldn't be visible as members outside unless they are declared as vals
          // compare: class Foo(x: Int) vs class Foo(val x: Int)
          enterTree(vparam, classSym, classSignatureLookupScopeContext)
        }
      }

      val lookupScopeContext = classSignatureLookupScopeContext.pushClassLookupScope(classSym)
      val completer = new TemplateMemberListCompleter(classSym, tmpl, lookupScopeContext.parentScope)
      queueCompleter(completer)
      classSym.completer = completer
      for (stat <- tmpl.body) enterTree(stat, classSym, lookupScopeContext)
    // type alias or type member
    case TypeDef(name, _) =>
      val typeSymbol = TypeDefSymbol(name, owner)
      owner.addChild(typeSymbol)
    case t@ValDef(name, _, _) =>
      val valSym = ValDefSymbol(name)
      val completer = new ValDefCompleter(valSym, t, parentLookupScopeContext.newValDefLookupScope(valSym))
      valSym.completer = completer
      owner.addChild(valSym)
    case t: DefDef =>
      val defSym = DefDefSymbol(t.name)
      var remainingTparams = t.tparams
      var tParamIndex = 0
      foreachWithIndex(t.tparams) { (tParam, tParamIndex) =>
        // TODO: setup completers for TypeDef (resolving bounds, etc.)
        defSym.typeParams.enter(TypeParameterSymbol(tParam.name, tParamIndex, owner))
      }
      val completer = new DefDefCompleter(defSym, t, parentLookupScopeContext.newDefDefLookupScope(defSym))
      defSym.completer = completer
      owner.addChild(defSym)
    case _ =>
  }

  private def expandQualifiedPackageDeclaration(pkgDecl: RefTree, owner: Symbol)(implicit ctx: Context): PackageSymbol =
    pkgDecl match {
    case Ident(name: Name) =>
      lookupOrCreatePackage(name, owner)
    case Select(qualifier: RefTree, name: Name) =>
      val qualPkg = expandQualifiedPackageDeclaration(qualifier, owner)
      lookupOrCreatePackage(name, qualPkg)
  }

  private def lookupOrCreatePackage(name: Name, owner: Symbol)(implicit ctx: Context): PackageSymbol = {
    /**
      * The line below for `resolvedOwner` is a hack borrowed from scalac and dottyc. It is hard
      * to understand so it receives a special, long comment. The long comment is cheaper to implement
      * than a more principled handling of empty packages hence the choice.
      *
      * In ac8cdb7e6f3040ba826da4e5479b3d75a7a6fa9e I tried to fix the interaction of
      * an empty package with other packages. In the commit message, I said:
      *
      *    Scala's parser has a weird corner case when both an empty package and
      *    regular one are involved in the same compilation unit:
      *
      *    // A.scala
      *    class A
      *    package foo {
      *      class B
      *    }
      *
      *    is expanded during parsing into:
      *    // A.scala
      *    package <empty> {
      *      class A
      *      package foo {
      *        class B
      *      }
      *    }
      *
      *    However, one would expect the actual ast representation to be:
      *
      *    package <empty> {
      *      class A
      *    }
      *    package foo {
      *      class B
      *    }
      *
      *    I believe `foo` is put into the empty package to preserve the property
      *    that a compilation unit has just one root ast node. Surprisingly, both
      *    the scalac and dottyc handle the scoping properly when the asts are
      *    nested in this weird fashion. For example, in scalac `B` won't see the
      *    `A` class even if it seems like it should when one looks at the nesting
      *    structure. I couldn't track down where the special logic that is
      *    responsible for special casing the empty package and ignoring the nesting
      *    structure.
      *
      * In the comment above, I was wrong. `B` class actually sees the `A` class when both are
      * declared in the same compilation unit. The `A` class becomes inaccessible to any other
      * members declared in a `foo` package (or any other package except for the empty package)
      * when these members are declared in a separate compilation unit. This is expected:
      * members declared in the same compilation unit are accessible to each other through
      * reference by a simple identifier. My fix in ac8cdb7e6f3040ba826da4e5479b3d75a7a6fa9e
      * was wrong based on this wrong analysis.
      *
      * The correct analysis is that scoping rules for the compilation unit should be preserved
      * so simply moving `package foo` declaration out of the empty package declaration is not
      * a good way to fix the problem that the `package foo` should not have the empty package
      * as an owner. The best fix would be at parsing time: the parser should create a top-level
      * ast not (called e.g. CompilationUnit) that would hold `class A` and `package foo`
      * verbatim as they're declared in source code. And only during entering symbols, the
      * empty package would be introduced for the `class A` with correct owners.
      *
      * Making this a reality would require changing parser's implementation which is hard so
      * we're borrowing a trick from scalac: whenever we're creating a package, we're checking
      * whether the owner is an empty package. If so, we're teleporting ourselves to the root
      * package. This, in effect, undoes the nesting into an empty package done by parser.
      * But it undoes it only for the owner chain hierarchy. The scoping rules are kept intact
      * so members in the same compilation unit are visible to each other.
      *
      * The same logic in scalac lives in the `createPackageSymbol` method at:
      *
      * https://github.com/scala/scala/blob/2.12.x/src/compiler/scala/tools/nsc/typechecker/Namers.scala#L341
      */
    val resolvedOwner = if (owner == ctx.definitions.emptyPackage) ctx.definitions.rootPackage else owner
    val lookedUp = resolvedOwner.lookup(name)
    lookedUp match {
      case pkgSym: PackageSymbol => pkgSym
      case _ =>
        val pkgSym = PackageSymbol(name)
        val pkgCompleter = new PackageCompleter(pkgSym)
        pkgSym.completer = pkgCompleter
        queueCompleter(pkgCompleter, pushToTheEnd = false)
        resolvedOwner.addChild(pkgSym)
        pkgSym
    }
  }

  def processJobQueue(memberListOnly: Boolean,
                      listener: JobQueueProgressListener = NopJobQueueProgressListener)(implicit ctx: Context):
    CompleterStats = {
    var steps = 0
    var missedDeps = 0
    try {
      while (!completers.isEmpty) {
        steps += 1
        if (ctx.verbose)
          println(s"Step $steps/${steps + completers.size - 1}")
        val completer = completers.remove()
        if (ctx.verbose)
          println(s"Trying to complete $completer")
        if (!completer.isCompleted) {
          val res = completer.complete()
          if (ctx.verbose)
            println(s"res = $res")
          if (res.isInstanceOf[IncompleteDependency]) {
            missedDeps += 1
          }
          res match {
            case CompletedType(tpe: ClassInfoType) =>
              val classSym = tpe.clsSym
              classSym.info = tpe
              if (!memberListOnly)
                scheduleMembersCompletion(classSym)
            case CompletedType(tpe: ModuleInfoType) =>
              val modSym = tpe.modSym
              modSym.info = tpe
            case IncompleteDependency(sym: ClassSymbol) =>
              assert(sym.completer != null, sym.name)
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: ModuleSymbol) =>
              assert(sym.completer != null, sym.name)
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: ValDefSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: DefDefSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case IncompleteDependency(sym: PackageSymbol) =>
              queueCompleter(sym.completer)
              queueCompleter(completer)
            case CompletedType(tpe: MethodInfoType) =>
              val defDefSym = completer.sym.asInstanceOf[DefDefSymbol]
              defDefSym.info = tpe
            case CompletedType(tpe: ValInfoType) =>
              val valDefSym = completer.sym.asInstanceOf[ValDefSymbol]
              valDefSym.info = tpe
            case CompletedType(tpe: PackageInfoType) =>
              val pkgSym = completer.sym.asInstanceOf[PackageSymbol]
              pkgSym.info = tpe
            // error cases
            case completed: CompletedType =>
              sys.error(s"Unexpected completed type $completed returned by completer for ${completer.sym}")
            case incomplete@IncompleteDependency(_: TypeDefSymbol) =>
              throw new UnsupportedOperationException("TypeDef support is not implemented yet")
            case incomplete@(IncompleteDependency(_: TypeParameterSymbol) | IncompleteDependency(NoSymbol) |
                             IncompleteDependency(_: PackageSymbol)) =>
              sys.error(s"Unexpected incomplete dependency $incomplete")
            case NotFound =>
              sys.error(s"The completer for ${completer.sym} finished with a missing dependency")
          }
        }
        listener.thick(completers.size, steps)
      }
    } catch {
      case ex: Exception =>
        println(s"steps = $steps, missedDeps = $missedDeps")
        throw ex
    }
    listener.allComplete()
    CompleterStats(steps, missedDeps)
  }

  private def scheduleMembersCompletion(sym: ClassSymbol)(implicit ctx: Context): Unit = {
    sym.decls.toList foreach {
      case defSym: DefDefSymbol => queueCompleter(defSym.completer)
      case valSym: ValDefSymbol => queueCompleter(valSym.completer)
      case _: ClassSymbol | _: ModuleSymbol =>
      case decl@(_: TypeDefSymbol) =>
        if (ctx.verbose)
          println(s"Ignoring type def $decl in ${sym.name}")
      case decl@(_: TypeParameterSymbol | _: PackageSymbol | NoSymbol) =>
        sys.error(s"Unexpected class declaration: $decl")
    }
  }

}

object Enter {
  import ast.untpd._

  sealed trait LookupAnswer
  case class LookedupSymbol(sym: Symbol) extends LookupAnswer
  case object NotFound extends LookupAnswer with CompletionResult

  sealed trait CompletionResult
  case class CompletedType(tpe: Type) extends CompletionResult

  case class IncompleteDependency(sym: Symbol) extends LookupAnswer with CompletionResult

  abstract class LookupScope {
    def lookup(name: Name)(implicit context: Context): LookupAnswer
    def enclosingClass: LookupAnswer
    def replaceImports(imports: ImportsLookupScope): LookupScope
  }

  case class CompleterStats(processedJobs: Int, dependencyMisses: Int)

  private class ImportCompleter(val importNode: Import) {

    private class ImportSelectorResolved(val termSym: Symbol, val typeSym: Symbol, renamedTo: Name) {
      val typeNameRenamed: TypeName = if (renamedTo != null) renamedTo.toTypeName else null
      val termNameRenamed: TermName = if (renamedTo != null) renamedTo.toTermName else null
    }
    private var exprSym0: Symbol = _
    private var resolvedSelectors: util.ArrayList[ImportSelectorResolved] = _
    private var hasFinalWildcard: Boolean = false
    private var isComplete: Boolean = false
    def complete(parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer = {
      val Import(expr, selectors) = importNode
      val exprAns = resolveSelectors(expr, parentLookupScope)
      val result = mapCompleteAnswer(exprAns) { exprSym =>
        this.exprSym0 = exprSym
        val resolvedSelectors = mapToArrayList(selectors) { selector =>
          val (name, renamedTo) = selector match {
            case Ident(selName) => (selName, selName)
            case Pair(Ident(selName), Ident(selRenamedTo)) => (selName, selRenamedTo)
          }
          if (name != nme.WILDCARD) {
            val termSym = lookupMember(exprSym, name)
            val typeSym = lookupMember(exprSym, name.toTypeName)
            if (termSym == NoSymbol && typeSym == NoSymbol)
              return NotFound
            new ImportSelectorResolved(termSym, typeSym, renamedTo)
          } else {
            // parser guarantees that the wildcard name can only appear at the end of
            // the selector list and we check for possible null value below
            // signalling the wildcard selector via a null value is really wonky but
            // I'm doing it for one reason: I'm paranoid about performance and don't
            // want to scan the selectors list twice
            null
          }
        }
        val selectorsSize = resolvedSelectors.size()
        if (selectorsSize > 0 && resolvedSelectors.get(selectorsSize-1) == null) {
          resolvedSelectors.remove(selectorsSize-1)
          hasFinalWildcard = true
        }
        this.resolvedSelectors = resolvedSelectors
        exprSym
      }
      isComplete = true
      result
    }
    def matches(name: Name)(implicit context: Context): Symbol = {
      assert(isComplete, s"the import node hasn't been completed: $importNode")
      var i = 0
      var seenNameInSelectors = false
      while (i < resolvedSelectors.size) {
        val selector = resolvedSelectors.get(i)
        val sym = {
          val termSym = selector.termSym
          val typeSym = selector.typeSym
          // all comparisons below are pointer equality comparisons; the || operator
          // has short-circuit optimization so this check, while verbose, is actually
          // really efficient
          if ((typeSym != null && (typeSym.name eq name)) || (termSym.name eq name)) {
            seenNameInSelectors = true
          }
          if (name.isTermName && termSym != null && (selector.termNameRenamed == name)) {
            seenNameInSelectors = true
            termSym
        } else if (typeSym != null && (selector.typeNameRenamed == name)) {
            seenNameInSelectors = true
            typeSym
          } else NoSymbol
        }
        if (sym != NoSymbol)
          return sym
        i += 1
      }
      // if not seen before, consider the final wildcard import
      // seenNameInSelector check is required by the spec (4.7):
      //   If a final wildcard is present, all importable members z
      //   z of p other than x1,…,xn,y1,…,yn
      //   x1, …, xn, y1,…,yn are also made available under their own unqualified names.
      if (!seenNameInSelectors && hasFinalWildcard) {
        exprSym0.info.lookup(name)
      } else NoSymbol
    }
  }

  private def lookupMember(sym: Symbol, name: Name)(implicit context: Context): Symbol = {
    assert(sym.isComplete, s"Can't look up a member $name in a symbol that is not completed yet: $sym")
    sym match {
      case clsSym: ClassSymbol => clsSym.info.lookup(name)
      case modSym: ModuleSymbol => modSym.info.lookup(name)
      case pkgSym: PackageSymbol => pkgSym.info.lookup(name)
      case valSym: ValDefSymbol => valSym.info.lookup(name)
      case _: TypeDefSymbol =>
        throw new UnsupportedOperationException("Support for type defs is not implemented yet")
      case _: DefDefSymbol =>
        sys.error("Selecting from unapplied defs is not legal in Scala")
      case _: TypeParameterSymbol =>
        // TODO: actually, not true, it makes sense to select members of a type parameter by looking at its upper bound
        // but this is not implemented yet
        // for example this is legal: class Foo[T <: Bar] { val x: T; val y: T.boo }; class Bar { val boo: ... }
        sys.error("Unexpected selection from type parameter symbol, it should have been substituted by type argument")
      case NoSymbol =>
        sys.error("Ooops. Trying to select a member of NoSymbol (this is a bug)")
    }
  }

  class ImportsCollector(parentLookupScope: LookupScope) {
    private val importCompleters: util.ArrayList[ImportCompleter] = new util.ArrayList[ImportCompleter]()
    def append(imp: Import): Unit = {
      importCompleters.add(new ImportCompleter(imp))
    }
    def snapshot(): ImportsLookupScope = {
      new ImportsLookupScope(importCompleters, parentLookupScope)()
    }
    def isEmpty: Boolean = importCompleters.isEmpty
  }

  class ImportsLookupScope(importCompleters: util.ArrayList[ImportCompleter], parentLookupScope: LookupScope)
                          (lastCompleterIndex: Int = importCompleters.size - 1) {
    private var allComplete: Boolean = false


    private def resolveImports()(implicit context: Context): Symbol = {
      var i: Int = 0
      while (i <= lastCompleterIndex) {
        val importsCompletedSoFar = new ImportsLookupScope(importCompleters, parentLookupScope)(lastCompleterIndex = i-1)
        importsCompletedSoFar.allComplete = true
        val parentLookupWithImports = parentLookupScope.replaceImports(importsCompletedSoFar)
        val impCompleter = importCompleters.get(i)
        impCompleter.complete(parentLookupWithImports) match {
          case _: LookedupSymbol =>
          case IncompleteDependency(sym) => return sym
          case NotFound => sys.error(s"couldn't resolve import ${impCompleter.importNode}")
        }
        i += 1
      }
      allComplete = true
      null
    }
    def lookup(name: Name)(implicit context: Context): LookupAnswer = {
      if (!allComplete) {
        val sym = resolveImports()
        if (sym != null)
          return IncompleteDependency(sym)
      }
      var i = lastCompleterIndex
      while (i >= 0) {
        val completedImport = importCompleters.get(i)
        val sym = completedImport.matches(name)
        if (sym != NoSymbol)
          return LookedupSymbol(sym)
        i -= 1
      }
      NotFound
    }
  }

  abstract class Completer(val sym: Symbol) {
    def complete()(implicit context: Context): CompletionResult
    def isCompleted: Boolean
    override def toString: String = s"${this.getClass.getName}: $sym"
  }

  class ModuleCompleter(modSym: ModuleSymbol) extends Completer(modSym) {
    private var cachedInfo: ModuleInfoType = _
    override def complete()(implicit context: Context): CompletionResult = {
      if (cachedInfo != null)
        CompletedType(cachedInfo)
      else if (!modSym.clsSym.isComplete)
        IncompleteDependency(modSym.clsSym)
      else {
        cachedInfo = new ModuleInfoType(modSym, modSym.clsSym.info)
        CompletedType(cachedInfo)
      }
    }
    override def isCompleted: Boolean = cachedInfo != null
  }

  class TemplateMemberListCompleter(val clsSym: ClassSymbol, tmpl: Template, val lookupScope: LookupScope) extends Completer(clsSym) {
    private var cachedInfo: ClassInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val resolvedParents = new util.ArrayList[Type]()
      var remainingParents = tmpl.parents
      while (remainingParents.nonEmpty) {
        val parent = remainingParents.head
        val resolved = resolveTypeTree(parent, lookupScope)
        resolved match {
          case CompletedType(tpe) => resolvedParents.add(tpe)
          case _: IncompleteDependency | NotFound => return resolved
        }
        remainingParents = remainingParents.tail
      }
      val info = new ClassInfoType(clsSym, asScalaList(resolvedParents))
      var i = 0
      while (i < resolvedParents.size()) {
        val parentType = resolvedParents.get(i)
        val parentSym = parentType.typeSymbol.asInstanceOf[ClassSymbol]
        val parentInfo = if (parentSym.info != null) parentSym.info else
          return IncompleteDependency(parentSym)
        parentType match {
          case at: AppliedType =>
            import TypeOps.{TypeParamMap, deriveMemberOfAppliedType}
            val typeParams = at.typeSymbol.asInstanceOf[ClassSymbol].typeParams
            val typeParamMap = new TypeParamMap(typeParams)
            for (m <- parentInfo.members.iterator) {
              if (!m.isComplete)
                return IncompleteDependency(m)
              val derivedInheritedMember = deriveMemberOfAppliedType(m, at, typeParamMap)
              info.members.enter(derivedInheritedMember)
            }
          case other =>
            info.members.enterAll(parentInfo.members)
        }
        i += 1
      }
      info.members.enterAll(clsSym.decls)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class PackageCompleter(pkgSym: PackageSymbol) extends Completer(pkgSym) {
    private var cachedInfo: PackageInfoType = _
    override def complete()(implicit context: Context): CompletionResult = {
      if (cachedInfo != null)
        CompletedType(cachedInfo)
      else if ((pkgSym.packageObject != NoSymbol) && (!pkgSym.packageObject.isComplete))
        IncompleteDependency(pkgSym.packageObject)
      else {
        val info = new PackageInfoType(pkgSym)
        // TODO: check for conflicting definitions in the package and package object, e.g.:
        // package foo { class Abc }; package object foo { class Abc }
        if (pkgSym.packageObject != NoSymbol)
          info.members.enterAll(pkgSym.packageObject.asInstanceOf[ModuleSymbol].info.members)
        info.members.enterAll(pkgSym.decls)

        cachedInfo = info
        CompletedType(cachedInfo)
      }
    }
    override def isCompleted: Boolean = cachedInfo != null
  }

  class DefDefCompleter(sym: DefDefSymbol, defDef: DefDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: MethodInfoType = _
    def complete()(implicit context: Context): CompletionResult = {
      val paramTypes = {
        // TODO: write interruptible map2, def interMap2[T](xss: List[List[T])(f: T => CompletionResult): List[List[Type]]
        defDef.vparamss map {  vParams =>
          vParams map { vparam =>
            val resolvedType = resolveTypeTree(vparam.tpt, lookupScope)
            resolvedType match {
              case CompletedType(tpe) => tpe
              case res: IncompleteDependency => return res
              case NotFound => sys.error(s"Couldn't resolve ${vparam.tpt}")
            }
          }
        }
      }
      val resultType: Type = if (defDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(defDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${defDef.tpt}")
        }
      }
      val info = MethodInfoType(sym, paramTypes, resultType)
      cachedInfo = info
      CompletedType(info)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  class ValDefCompleter(sym: ValDefSymbol, valDef: ValDef, val lookupScope: LookupScope) extends Completer(sym) {
    private var cachedInfo: ValInfoType = _
    def complete()(implicit context: Context): CompletionResult = try {
      val resultType: Type = if (valDef.tpt.isEmpty) InferredTypeMarker else {
        val resolvedType = resolveTypeTree(valDef.tpt, lookupScope)
        resolvedType match {
          case CompletedType(tpe) => tpe
          case res: IncompleteDependency => return res
          case NotFound => sys.error(s"Couldn't resolve ${valDef.tpt}")
        }
      }
      val info = ValInfoType(sym, resultType)
      cachedInfo = info
      CompletedType(info)
    } catch {
      case ex: Exception =>
        throw new RuntimeException(s"Error while completing $valDef", ex)
    }
    def isCompleted: Boolean = cachedInfo != null
  }

  private def resolveSelectors(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): LookupAnswer =
    t match {
      case Ident(identName) => parentLookupScope.lookup(identName)
      case Select(qual, selName) =>
        val ans = resolveSelectors(qual, parentLookupScope)
        ans match {
          case LookedupSymbol(qualSym) =>
            if (qualSym.isComplete) {
              val selSym = qualSym.info.lookup(selName)
              if (selSym != NoSymbol)
                LookedupSymbol(selSym)
              else
                NotFound
            } else IncompleteDependency(qualSym)
          case _ => ans
        }
      // TODO: right now we interpret C.super[M] as just C.super (M is ignored)
      case Super(qual, _) => resolveSelectors(qual, parentLookupScope)
      case This(tpnme.EMPTY) => parentLookupScope.enclosingClass
      case This(thisQual) => parentLookupScope.lookup(thisQual)
      case _ => sys.error(s"Unhandled tree $t at ${t.pos}")
    }

  private def resolveTypeTree(t: Tree, parentLookupScope: LookupScope)(implicit context: Context): CompletionResult = t match {
    case AppliedTypeTree(tpt, args) =>
      val resolvedTpt = resolveTypeTree(tpt, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case uncompleted => return uncompleted
      }
      var remainingArgs = args
      val resolvedArgs = new util.ArrayList[Type]()
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedArgs.add (argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      CompletedType(AppliedType(resolvedTpt, resolvedArgs.toArray(new Array[Type](resolvedArgs.size))))
    // ParentClass(foo) is encoded as a constructor call with a tree of shape
    // Apply(Select(New(Ident(ParentClass)),<init>),List(Ident(foo)))
    // we want to extract the Ident(ParentClass)
    case Apply(Select(New(tp), nme.CONSTRUCTOR), _) =>
      resolveTypeTree(tp, parentLookupScope)
    // strip down any constructor applications, e.g.
    // class Foo extends Bar[T]()(t)
    case Apply(qual, _) =>
      resolveTypeTree(qual, parentLookupScope)
    case Parens(t2) => resolveTypeTree(t2, parentLookupScope)
    case Function(args, res) =>
      val resolvedFunTypeArgs = new util.ArrayList[Type]()
      var remainingArgs = args
      while (remainingArgs.nonEmpty) {
        val resolvedArg = resolveTypeTree(remainingArgs.head, parentLookupScope)
        resolvedArg match {
          case CompletedType(argTpe) => resolvedFunTypeArgs.add(argTpe)
          case _ => return resolvedArg
        }
        remainingArgs = remainingArgs.tail
      }
      val resolvedRes = resolveTypeTree(res, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val funName = functionNamesByArity(resolvedFunTypeArgs.size)
      resolvedFunTypeArgs.add(resolvedRes)
      val functionSym = parentLookupScope.lookup(funName) match {
        case LookedupSymbol(sym) => sym
        case NotFound => sys.error(s"Can't resolve $funName")
        case x: IncompleteDependency => return x
      }
      CompletedType(AppliedType(SymRef(functionSym), resolvedFunTypeArgs.toArray[Type](new Array(resolvedFunTypeArgs.size))))
    // TODO: I ignore a star indicator of a repeated parameter as it's not essential and fairly trivial to deal with
    case PostfixOp(ident, nme.raw.STAR) =>
      resolveTypeTree(ident, parentLookupScope)
    case Tuple(trees) =>
      var remainingTrees = trees
      val resolvedTrees = new util.ArrayList[Type]()
      while (remainingTrees.nonEmpty) {
        val resolvedTree = resolveTypeTree(remainingTrees.head, parentLookupScope)
        resolvedTree match {
          case CompletedType(treeTpe) => resolvedTrees.add(treeTpe)
          case _ => return resolvedTree
        }
        remainingTrees = remainingTrees.tail
      }
      CompletedType(TupleType(resolvedTrees.toArray(new Array[Type](resolvedTrees.size))))
    // TODO: we ignore by name argument `=> T` and resolve it as `T`
    case ByNameTypeTree(res) =>
      resolveTypeTree(res, parentLookupScope)
    // TODO: I ignore AndTypeTree and pick just the left side, for example the `T with U` is resolved to `T`
    case t@AndTypeTree(left, right) =>
      if (context.verbose)
        println(s"Ignoring $t (printed because this hacky shortcut is non-trivial)")
      resolveTypeTree(left, parentLookupScope)
    case TypeBoundsTree(EmptyTree, EmptyTree) =>
      CompletedType(WildcardType)
    case InfixOp(left, op, right) =>
      val resolvedLeftType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedRightType = resolveTypeTree(left, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedOp = parentLookupScope.lookup(op) match {
        case LookedupSymbol(sym) => SymRef(sym)
        case NotFound =>
          sys.error(s"Can't resolve $op")
        case incomplete: IncompleteDependency => return incomplete
      }
      CompletedType(AppliedType(resolvedOp, Array[Type](resolvedLeftType, resolvedRightType)))
    case SelectFromTypeTree(qualifier, name) =>
      val resolvedQualifier = resolveTypeTree(qualifier, parentLookupScope) match {
        case CompletedType(tpe) => tpe
        case other => return other
      }
      val resolvedSelect = if (resolvedQualifier.typeSymbol.isComplete)
        resolvedQualifier.typeSymbol.info.lookup(name)
      else
        return IncompleteDependency(resolvedQualifier.typeSymbol)
      if (resolvedSelect != NoSymbol)
        CompletedType(SymRef(resolvedSelect))
      else
        NotFound
    case SingletonTypeTree(ref) =>
      def symRefAsCompletionResult(sym: Symbol): CompletionResult =
        if (sym.isComplete) CompletedType(SymRef(sym)) else IncompleteDependency(sym)
      val result = resolveTypeTree(ref, parentLookupScope)
      result match {
        case CompletedType(resolvedPath) => resolvedPath match {
          case SymRef(cls: ClassSymbol) => symRefAsCompletionResult(cls)
          case SymRef(mod: ModuleSymbol) => symRefAsCompletionResult(mod.clsSym)
          case SymRef(valDef: ValDefSymbol) =>
            if (valDef.isComplete) CompletedType(valDef.info.resultType) else IncompleteDependency(valDef)
        }
        case other => other
      }
    case Annotated(_, arg) => resolveTypeTree(arg, parentLookupScope)
    // TODO: refinements are simply dropped at the moment
    case RefinedTypeTree(tpt, _) => resolveTypeTree(tpt, parentLookupScope)
    case This(tpnme.EMPTY) =>
      val resolvedCls = parentLookupScope.enclosingClass
      resolvedCls match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve This at ${t.pos}")
        case incomplete: IncompleteDependency => incomplete
      }
    // idnet or select?
    case other =>
      val resolvedSel = resolveSelectors(other, parentLookupScope)
      resolvedSel match {
        case LookedupSymbol(sym) => CompletedType(SymRef(sym))
        case NotFound =>
          sys.error(s"Can't resolve selector $other")
        case incomplete: IncompleteDependency => incomplete
      }
  }

  trait JobQueueProgressListener {
    def thick(queueSize: Int, completed: Int): Unit
    def allComplete(): Unit
  }
  object NopJobQueueProgressListener extends JobQueueProgressListener {
    override def thick(queueSize: Int, completed: Int): Unit = ()
    override def allComplete(): Unit = ()
  }

  private def asScalaList[T](javaList: util.ArrayList[T]): List[T] = {
    var i = javaList.size() - 1
    var res: List[T] = Nil
    while (i >= 0) {
      res = javaList.get(i) :: res
      i -= 1
    }
    res
  }

  private def asScalaList2[T](javaList: util.ArrayList[util.ArrayList[T]]): List[List[T]] = {
    var i = javaList.size() - 1
    var res: List[List[T]] = Nil
    while (i >= 0) {
      val innerList = asScalaList(javaList.get(i))
      res = innerList :: res
      i -= 1
    }
    res
  }

  @inline final private def foreachWithIndex[T](xs: List[T])(f: (T, Int) => Unit): Unit = {
    var index = 0
    var remaining = xs
    while (remaining.nonEmpty) {
      f(remaining.head, index)
      remaining = remaining.tail
      index += 1
    }
  }

  @inline final private def mapCompleteAnswer(ans: LookupAnswer)(f: Symbol => Symbol): LookupAnswer = {
    ans match {
      case LookedupSymbol(sym) =>
        if (sym.isComplete)
           LookedupSymbol(f(sym))
        else
          IncompleteDependency(sym)
      case other => other
    }
  }

  @inline final private def mapToArrayList[T, U](xs: List[T])(f: T => U): util.ArrayList[U] = {
    val result = new util.ArrayList[U]()
    var remaining = xs
    while (remaining.nonEmpty) {
      val elem = f(remaining.head)
      result.add(elem)
      remaining = remaining.tail
    }
    result
  }

  private val maxFunctionArity = 22
  val functionNamesByArity: Array[TypeName] = Array.tabulate(maxFunctionArity+1)(i => s"Function$i".toTypeName)
}
