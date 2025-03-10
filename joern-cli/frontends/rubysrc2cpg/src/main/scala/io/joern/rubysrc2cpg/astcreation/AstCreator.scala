package io.joern.rubysrc2cpg.astcreation
import io.joern.rubysrc2cpg.parser.RubyParser
import io.joern.rubysrc2cpg.parser.RubyParser.*
import io.joern.rubysrc2cpg.passes.Defines
import io.joern.rubysrc2cpg.utils.PackageContext
import io.joern.x2cpg.Ast.storeInDiffGraph
import io.joern.x2cpg.Defines.DynamicCallUnknownFullName
import io.joern.x2cpg.X2Cpg.stripQuotes
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.{Ast, AstCreatorBase, AstNodeBuilder, ValidationMode, Defines as XDefines}
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.antlr.v4.runtime.ParserRuleContext
import org.slf4j.LoggerFactory
import overflowdb.BatchedUpdate

import java.io.File as JFile
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

class AstCreator(
  filename: String,
  global: Global, // TODO: This needs to register types
  parser: ResourceManagedParser,
  packageContext: PackageContext,
  projectRoot: Option[String] = None
)(implicit withSchemaValidation: ValidationMode)
    extends AstCreatorBase(filename)
    with AstNodeBuilder[ParserRuleContext, AstCreator]
    with AstForPrimitivesCreator
    with AstForStatementsCreator(filename)
    with AstForFunctionsCreator(packageContext)
    with AstForExpressionsCreator
    with AstForDeclarationsCreator
    with AstForTypesCreator
    with AstForControlStructuresCreator
    with AstCreatorHelper
    with AstForHereDocsCreator {

  protected val scope: RubyScope = new RubyScope()

  private val logger = LoggerFactory.getLogger(this.getClass)

  protected val classStack: mutable.Stack[String] = mutable.Stack[String]()

  protected val packageStack: mutable.Stack[String] = mutable.Stack[String]()

  protected val pathSep = "."

  protected val relativeFilename: String =
    projectRoot.map(filename.stripPrefix).map(_.stripPrefix(JFile.separator)).getOrElse(filename)

  // The below are for adding implicit return nodes to methods

  // This is true if the last statement of a method is being processed. The last statement could be a if-else as well
  protected val processingLastMethodStatement: AtomicBoolean = AtomicBoolean(false)
  // a monotonically increasing block id unique within this file
  protected val blockIdCounter: AtomicInteger = AtomicInteger(1)
  // block id of the block currently being processed
  protected val currentBlockId: AtomicInteger = AtomicInteger(0)
  /*
   * This is a hash of parent block id ---> child block id. If there are multiple children, any one child can be present.
   * The value of this entry for a block is read AFTER its last statement has been processed. Absence of the the block
   * in this hash implies this is a leaf block.
   */
  protected val blockChildHash: mutable.Map[Int, Int] = mutable.HashMap[Int, Int]()

  private val builtInCallNames = mutable.HashSet[String]()
  // Hashmap to store used variable names, to avoid duplicates in case of un-named variables
  protected val usedVariableNames = mutable.HashMap.empty[String, Int]

  override def createAst(): BatchedUpdate.DiffGraphBuilder = {
    parser.parse(filename) match {
      case Success(programCtx) =>
        createAstForProgramCtx(programCtx)
      case Failure(exc) =>
        logger.warn(s"Could not parse file: $filename, skipping", exc)
        diffGraph
    }
  }

  private def createAstForProgramCtx(programCtx: RubyParser.ProgramContext) = {
    val name     = ":program"
    val fullName = s"$relativeFilename:$name"
    val programMethod =
      methodNode(
        programCtx,
        name,
        name,
        fullName,
        None,
        relativeFilename,
        Option(NodeTypes.TYPE_DECL),
        Option(fullName)
      )

    classStack.push(fullName)
    scope.pushNewScope(programMethod)

    val statementAsts =
      if (
        programCtx.compoundStatement() != null &&
        programCtx.compoundStatement().statements() != null
      ) {
        val stmts       = astForStatements(programCtx.compoundStatement().statements(), false, false)
        val methodNodes = stmts.flatMap(_.nodes).collect { case x: NewMethod => x }.toSet
        // Block methods is largely unnecessary, but will keep it for AST functions that still populate it instead of
        // attaching it to AST
        stmts ++ blockMethods.filterNot(_.root.collect { case x: NewMethod => x }.exists(methodNodes.contains))
      } else {
        logger.error(s"File $filename has no compound statement. Needs to be examined")
        List[Ast](Ast())
      }

    val methodRetNode = methodReturnNode(programCtx, Defines.Any)

    // For all the builtIn's encountered create assignment ast, minus user-defined methods with the same name
    val lineColNum = 1
    val builtInMethodAst = builtInCallNames
      .filterNot(methodNameToMethod.contains)
      .map { builtInCallName =>
        val identifierNode = NewIdentifier()
          .code(builtInCallName)
          .name(builtInCallName)
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)
          .typeFullName(Defines.Any)
        scope.addToScope(builtInCallName, identifierNode)
        val typeRefNode = NewTypeRef()
          .code(prefixAsBuiltin(builtInCallName))
          .typeFullName(prefixAsBuiltin(builtInCallName))
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)
        astForAssignment(identifierNode, typeRefNode, Some(lineColNum), Some(lineColNum))
      }
      .toList

    val methodRefAssignmentAsts = methodNameToMethod.values
      .filterNot(_.astParentType == NodeTypes.TYPE_DECL)
      .map { methodNode =>
        // Create a methodRefNode and assign it to the identifier version of the method, which will help in type propagation to resolve calls
        methodRefAssignmentFromMethod(methodNode, Option(lineColNum), Option(lineColNum))
      }
      .toList

    val typeRefAssignmentAst = typeDeclNameToTypeDecl.values.map { typeDeclNode =>

      val typeRefNode = NewTypeRef()
        .code("class " + typeDeclNode.name + "(...)")
        .typeFullName(typeDeclNode.fullName)
        .lineNumber(typeDeclNode.lineNumber)
        .columnNumber(typeDeclNode.columnNumber)

      val typeDeclNameIdentifier = NewIdentifier()
        .code(typeDeclNode.name)
        .name(typeDeclNode.name)
        .typeFullName(Defines.Any)
        .lineNumber(lineColNum)
        .columnNumber(lineColNum)
      scope.addToScope(typeDeclNode.name, typeDeclNameIdentifier)
      val typeRefAssignmentAst =
        astForAssignment(typeDeclNameIdentifier, typeRefNode, typeDeclNode.lineNumber, typeDeclNode.columnNumber)
      typeRefAssignmentAst
    }

    val methodDefInArgumentAsts = methodDefInArgument.toList
    val locals                  = scope.createAndLinkLocalNodes(diffGraph).map(Ast.apply)
    val programAst =
      methodAst(
        programMethod,
        Seq.empty[Ast],
        blockAst(
          blockNode(programCtx),
          locals ++ builtInMethodAst ++ methodRefAssignmentAsts ++ typeRefAssignmentAst ++ methodDefInArgumentAsts ++ statementAsts.toList
        ),
        methodRetNode
      )

    scope.popScope()

    val fileNode       = NewFile().name(relativeFilename).order(1)
    val namespaceBlock = globalNamespaceBlock()
    val ast            = Ast(fileNode).withChild(Ast(namespaceBlock).withChild(programAst))

    classStack.popAll()

    storeInDiffGraph(ast, diffGraph)
    diffGraph
  }

  def astForPrimaryContext(ctx: PrimaryContext): Seq[Ast] = ctx match {
    case ctx: ClassDefinitionPrimaryContext if ctx.hasClassDefinition => astForClassDeclaration(ctx)
    case ctx: ClassDefinitionPrimaryContext                           => astForClassExpression(ctx)
    case ctx: ModuleDefinitionPrimaryContext                          => astForModuleDefinitionPrimaryContext(ctx)
    case ctx: MethodDefinitionPrimaryContext => astForMethodDefinitionContext(ctx.methodDefinition())
    case ctx: ProcDefinitionPrimaryContext   => astForProcDefinitionContext(ctx.procDefinition())
    case ctx: YieldWithOptionalArgumentPrimaryContext =>
      astForYieldCall(ctx, Option(ctx.yieldWithOptionalArgument().arguments()))
    case ctx: IfExpressionPrimaryContext     => Seq(astForIfExpression(ctx.ifExpression()))
    case ctx: UnlessExpressionPrimaryContext => Seq(astForUnlessExpression(ctx.unlessExpression()))
    case ctx: CaseExpressionPrimaryContext   => astForCaseExpressionPrimaryContext(ctx)
    case ctx: WhileExpressionPrimaryContext  => Seq(astForWhileExpression(ctx.whileExpression()))
    case ctx: UntilExpressionPrimaryContext  => Seq(astForUntilExpression(ctx.untilExpression()))
    case ctx: ForExpressionPrimaryContext    => Seq(astForForExpression(ctx.forExpression()))
    case ctx: ReturnWithParenthesesPrimaryContext =>
      Seq(returnAst(returnNode(ctx, text(ctx)), astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())))
    case ctx: JumpExpressionPrimaryContext     => astForJumpExpressionPrimaryContext(ctx)
    case ctx: BeginExpressionPrimaryContext    => astForBeginExpressionPrimaryContext(ctx)
    case ctx: GroupingExpressionPrimaryContext => astForCompoundStatement(ctx.compoundStatement(), false, false)
    case ctx: VariableReferencePrimaryContext  => Seq(astForVariableReference(ctx.variableReference()))
    case ctx: SimpleScopedConstantReferencePrimaryContext =>
      astForSimpleScopedConstantReferencePrimaryContext(ctx)
    case ctx: ChainedScopedConstantReferencePrimaryContext =>
      astForChainedScopedConstantReferencePrimaryContext(ctx)
    case ctx: ArrayConstructorPrimaryContext       => astForArrayLiteral(ctx.arrayConstructor())
    case ctx: HashConstructorPrimaryContext        => astForHashConstructorPrimaryContext(ctx)
    case ctx: LiteralPrimaryContext                => astForLiteralPrimaryExpression(ctx)
    case ctx: StringExpressionPrimaryContext       => astForStringExpression(ctx.stringExpression)
    case ctx: QuotedStringExpressionPrimaryContext => astForQuotedStringExpression(ctx.quotedStringExpression)
    case ctx: RegexInterpolationPrimaryContext =>
      astForRegexInterpolationPrimaryContext(ctx.regexInterpolation)
    case ctx: QuotedRegexInterpolationPrimaryContext  => astForQuotedRegexInterpolation(ctx.quotedRegexInterpolation)
    case ctx: IsDefinedPrimaryContext                 => astForIsDefinedPrimaryExpression(ctx)
    case ctx: SuperExpressionPrimaryContext           => astForSuperExpression(ctx)
    case ctx: IndexingExpressionPrimaryContext        => astForIndexingExpressionPrimaryContext(ctx)
    case ctx: MethodOnlyIdentifierPrimaryContext      => astForMethodOnlyIdentifier(ctx.methodOnlyIdentifier())
    case ctx: InvocationWithBlockOnlyPrimaryContext   => astForInvocationWithBlockOnlyPrimaryContext(ctx)
    case ctx: InvocationWithParenthesesPrimaryContext => astForInvocationWithParenthesesPrimaryContext(ctx)
    case ctx: ChainedInvocationPrimaryContext         => astForChainedInvocationPrimaryContext(ctx)
    case ctx: ChainedInvocationWithoutArgumentsPrimaryContext =>
      astForChainedInvocationWithoutArgumentsPrimaryContext(ctx)
    case _ =>
      logger.error(s"astForPrimaryContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForExpressionContext(ctx: ExpressionContext): Seq[Ast] = ctx match {
    case ctx: PrimaryExpressionContext             => astForPrimaryContext(ctx.primary())
    case ctx: UnaryExpressionContext               => astForUnaryExpression(ctx)
    case ctx: PowerExpressionContext               => astForPowerExpression(ctx)
    case ctx: UnaryMinusExpressionContext          => astForUnaryMinusExpression(ctx)
    case ctx: MultiplicativeExpressionContext      => astForMultiplicativeExpression(ctx)
    case ctx: AdditiveExpressionContext            => astForAdditiveExpression(ctx)
    case ctx: BitwiseShiftExpressionContext        => astForBitwiseShiftExpression(ctx)
    case ctx: BitwiseAndExpressionContext          => astForBitwiseAndExpression(ctx)
    case ctx: BitwiseOrExpressionContext           => astForBitwiseOrExpression(ctx)
    case ctx: RelationalExpressionContext          => astForRelationalExpression(ctx)
    case ctx: EqualityExpressionContext            => astForEqualityExpression(ctx)
    case ctx: OperatorAndExpressionContext         => astForAndExpression(ctx)
    case ctx: OperatorOrExpressionContext          => astForOrExpression(ctx)
    case ctx: RangeExpressionContext               => astForRangeExpressionContext(ctx)
    case ctx: ConditionalOperatorExpressionContext => Seq(astForTernaryConditionalOperator(ctx))
    case ctx: SingleAssignmentExpressionContext    => astForSingleAssignmentExpressionContext(ctx)
    case ctx: MultipleAssignmentExpressionContext  => astForMultipleAssignmentExpressionContext(ctx)
    case ctx: IsDefinedExpressionContext           => astForIsDefinedExpression(ctx)
    case _ =>
      logger.error(s"astForExpressionContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  protected def astForIndexingArgumentsContext(ctx: IndexingArgumentsContext): Seq[Ast] = ctx match {
    case ctx: RubyParser.CommandOnlyIndexingArgumentsContext =>
      astForCommand(ctx.command())
    case ctx: RubyParser.ExpressionsOnlyIndexingArgumentsContext =>
      ctx
        .expressions()
        .expression()
        .asScala
        .flatMap(astForExpressionContext)
        .toSeq
    case ctx: RubyParser.ExpressionsAndSplattingIndexingArgumentsContext =>
      val expAsts = ctx
        .expressions()
        .expression()
        .asScala
        .flatMap(astForExpressionContext)
        .toSeq
      val splatAsts            = astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
      val callNode             = createOpCall(ctx.COMMA, Operators.arrayInitializer, text(ctx))
      val (argAsts, otherAsts) = (expAsts ++ splatAsts).partitionExprAst
      otherAsts :+ callAst(callNode, argAsts)
    case ctx: AssociationsOnlyIndexingArgumentsContext =>
      astForAssociationsContext(ctx.associations())
    case ctx: RubyParser.SplattingOnlyIndexingArgumentsContext =>
      astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
    case _ =>
      logger.error(s"astForIndexingArgumentsContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  private def astForBeginExpressionPrimaryContext(ctx: BeginExpressionPrimaryContext): Seq[Ast] =
    astForBodyStatementContext(ctx.beginExpression().bodyStatement())

  private def astForChainedInvocationPrimaryContext(ctx: ChainedInvocationPrimaryContext): Seq[Ast] = {
    val hasBlockStmt = ctx.block() != null
    val primaryAst   = astForPrimaryContext(ctx.primary())
    val methodNameAst =
      if (!hasBlockStmt && text(ctx.methodName()) == "new") astForCallToConstructor(ctx.methodName(), primaryAst)
      else astForMethodNameContext(ctx.methodName())

    val terminalNode = if (ctx.COLON2() != null) {
      ctx.COLON2()
    } else if (ctx.DOT() != null) {
      ctx.DOT()
    } else {
      ctx.AMPDOT()
    }

    val (argsAst, otherAst) = (if (ctx.argumentsWithParentheses() != null) {
                                 astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())
                               } else {
                                 Seq()
                               }).partitionExprAst

    if (hasBlockStmt) {
      val blockName = methodNameAst.head.nodes.head
        .asInstanceOf[NewCall]
        .name
      val blockMethodName = blockName + terminalNode.getSymbol.getLine
      val blockMethodAsts =
        astForBlockFunction(
          ctxStmt = ctx.block().compoundStatement.statements(),
          ctxParam = ctx.block().blockParameter,
          blockMethodName,
          line(ctx).head,
          column(ctx).head,
          lineEnd(ctx).head,
          columnEnd(ctx).head
        )

      blockMethodAsts :+ blockMethodAsts
        .flatMap(_.nodes)
        .collectFirst { case methodRefNode: NewMethodRef =>
          val callNode = NewCall()
            .name(blockName)
            .methodFullName(methodRefNode.methodFullName)
            .typeFullName(Defines.Any)
            .code(methodRefNode.code)
            .lineNumber(methodRefNode.lineNumber)
            .columnNumber(methodRefNode.columnNumber)
            .dispatchType(DispatchTypes.STATIC_DISPATCH)
          // TODO: primaryAst.headOption is broken when primaryAst is an array
          callAst(callNode, argsAst ++ Seq(Ast(methodRefNode.copy)), primaryAst.headOption)
        }
        .getOrElse(Ast())
    } else {
      val callNode = methodNameAst.head.nodes
        .filter(node => node.isInstanceOf[NewCall])
        .head
        .asInstanceOf[NewCall]

      if (callNode.name == "call" && ctx.primary().isInstanceOf[ProcDefinitionPrimaryContext]) {
        // this is a proc.call
        val baseCallNode = primaryAst.head.nodes.head.asInstanceOf[NewCall]
        otherAst :+ callAst(baseCallNode, argsAst)
      } else {
        callNode
          .code(text(ctx))
          .lineNumber(terminalNode.lineNumber)
          .columnNumber(terminalNode.columnNumber)

        primaryAst.headOption.flatMap(_.root) match {
          case Some(methodNode: NewMethod) =>
            val methodRefNode = NewMethodRef()
              .code("def " + methodNode.name + "(...)")
              .methodFullName(methodNode.fullName)
              .typeFullName(Defines.Any)
            blockMethods.addOne(primaryAst.head)
            otherAst :+ callAst(callNode, Seq(Ast(methodRefNode)) ++ argsAst)
          case _ =>
            otherAst :+ callAst(callNode, argsAst, primaryAst.headOption)
        }
      }
    }
  }

  private def astForCallToConstructor(ctx: MethodNameContext, receiverAst: Seq[Ast]): Seq[Ast] = {
    val receiverTypeName = receiverAst.flatMap(_.root).collectFirst { case x: NewIdentifier => x } match
      case Some(receiverNode) if receiverNode.typeFullName != Defines.Any =>
        receiverNode.typeFullName
      case Some(receiverNode) if typeDeclNameToTypeDecl.contains(receiverNode.name) =>
        typeDeclNameToTypeDecl(receiverNode.name).fullName
      case _ => Defines.Any

    val name = XDefines.ConstructorMethodName
    val (methodFullName, typeFullName) =
      if (receiverTypeName != Defines.Any)
        (Seq(receiverTypeName, XDefines.ConstructorMethodName).mkString(pathSep), receiverTypeName)
      else (XDefines.DynamicCallUnknownFullName, Defines.Any)

    val constructorCall =
      callNode(ctx, text(ctx), name, methodFullName, DispatchTypes.STATIC_DISPATCH, None, Option(typeFullName))
    Seq(Ast(constructorCall))
  }

  def astForChainedInvocationWithoutArgumentsPrimaryContext(
    ctx: ChainedInvocationWithoutArgumentsPrimaryContext
  ): Seq[Ast] = {
    val methodNameAst = astForMethodNameContext(ctx.methodName())
    val baseAst       = astForPrimaryContext(ctx.primary())

    val blocksAst = if (ctx.block() != null) {
      astForBlock(ctx.block())
    } else {
      Seq()
    }
    val callNode = methodNameAst.head.nodes.filter(node => node.isInstanceOf[NewCall]).head.asInstanceOf[NewCall]
    callNode
      .code(text(ctx))
      .lineNumber(ctx.COLON2.lineNumber)
      .columnNumber(ctx.COLON2.columnNumber)
    val (argsAst, otherAst) = (baseAst ++ blocksAst).partitionExprAst
    otherAst :+ callAst(callNode, argsAst)
  }

  private def astForChainedScopedConstantReferencePrimaryContext(
    ctx: ChainedScopedConstantReferencePrimaryContext
  ): Seq[Ast] = {
    val primaryAst = astForPrimaryContext(ctx.primary())
    val localVar   = ctx.CONSTANT_IDENTIFIER()
    val varSymbol  = localVar.getSymbol
    val node     = createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))
    val constAst = Ast(node)

    val operatorName = getOperatorName(ctx.COLON2().getSymbol)
    val callNode     = createOpCall(ctx.COLON2, operatorName, text(ctx))
    Seq(callAst(callNode, primaryAst ++ Seq(constAst)))
  }

  private def astForGroupedLeftHandSideContext(ctx: GroupedLeftHandSideContext): Seq[Ast] = {
    astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide())
  }

  private def astForPackingLeftHandSideContext(ctx: PackingLeftHandSideContext): Seq[Ast] = {
    astForSingleLeftHandSideContext(ctx.singleLeftHandSide())
  }

  def astForMultipleLeftHandSideContext(ctx: MultipleLeftHandSideContext): Seq[Ast] = ctx match {
    case ctx: MultipleLeftHandSideAndpackingLeftHandSideMultipleLeftHandSideContext =>
      val multipleLHSAsts = ctx.multipleLeftHandSideItem.asScala.flatMap { item =>
        if (item.singleLeftHandSide != null) {
          astForSingleLeftHandSideContext(item.singleLeftHandSide())
        } else {
          astForGroupedLeftHandSideContext(item.groupedLeftHandSide())
        }
      }.toList

      val paramAsts =
        if (ctx.packingLeftHandSide() != null) {
          val packingLHSAst = astForPackingLeftHandSideContext(ctx.packingLeftHandSide())
          multipleLHSAsts ++ packingLHSAst
        } else {
          multipleLHSAsts
        }

      paramAsts

    case ctx: PackingLeftHandSideOnlyMultipleLeftHandSideContext =>
      astForPackingLeftHandSideContext(ctx.packingLeftHandSide())
    case ctx: GroupedLeftHandSideOnlyMultipleLeftHandSideContext =>
      astForGroupedLeftHandSideContext(ctx.groupedLeftHandSide())
    case _ =>
      logger.error(s"astForMultipleLeftHandSideContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  // TODO: Clean-up and take into account other hash elements
  private def astForHashConstructorPrimaryContext(ctx: HashConstructorPrimaryContext): Seq[Ast] = {
    if (ctx.hashConstructor().hashConstructorElements() == null) return Seq(Ast())
    val hashCtorElemCtxs = ctx.hashConstructor().hashConstructorElements().hashConstructorElement().asScala
    val associationCtxs  = hashCtorElemCtxs.filter(_.association() != null).map(_.association()).toSeq
    val expressionCtxs   = hashCtorElemCtxs.filter(_.expression() != null).map(_.expression()).toSeq
    expressionCtxs.flatMap(astForExpressionContext) ++ associationCtxs.flatMap(astForAssociationContext)
  }

  def astForInvocationExpressionOrCommandContext(ctx: InvocationExpressionOrCommandContext): Seq[Ast] = {
    if (ctx.EMARK() != null) {
      val invocWOParenAsts = astForInvocationWithoutParenthesesContext(ctx.invocationWithoutParentheses())
      val operatorName     = getOperatorName(ctx.EMARK().getSymbol)
      val callNode         = createOpCall(ctx.EMARK, operatorName, text(ctx))
      Seq(callAst(callNode, invocWOParenAsts))
    } else {
      astForInvocationWithoutParenthesesContext(ctx.invocationWithoutParentheses())
    }
  }

  private def astForInvocationWithoutParenthesesContext(ctx: InvocationWithoutParenthesesContext): Seq[Ast] =
    ctx match {
      case ctx: SingleCommandOnlyInvocationWithoutParenthesesContext => astForCommand(ctx.command())
      case ctx: ChainedCommandDoBlockInvocationWithoutParenthesesContext =>
        astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock())
      case ctx: ReturnArgsInvocationWithoutParenthesesContext =>
        val retNode = NewReturn()
          .code(text(ctx))
          .lineNumber(ctx.RETURN().getSymbol.getLine)
          .columnNumber(ctx.RETURN().getSymbol.getCharPositionInLine)
        val argAst = Option(ctx.arguments).map(astForArguments).getOrElse(Seq())
        Seq(returnAst(retNode, argAst))
      case ctx: BreakArgsInvocationWithoutParenthesesContext =>
        astForBreakArgsInvocation(ctx)
      case ctx: NextArgsInvocationWithoutParenthesesContext =>
        astForNextArgsInvocation(ctx)
      case _ =>
        logger.error(
          s"astForInvocationWithoutParenthesesContext() $relativeFilename, ${text(ctx)} All contexts mismatched."
        )
        Seq(Ast())
    }

  private def astForInvocationWithBlockOnlyPrimaryContext(ctx: InvocationWithBlockOnlyPrimaryContext): Seq[Ast] = {
    val methodIdAst = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    val blockName = methodIdAst.head.nodes.head
      .asInstanceOf[NewCall]
      .name

    val isYieldMethod = if (blockName.endsWith(Defines.YIELD_SUFFIX)) {
      val lookupMethodName = blockName.take(blockName.length - Defines.YIELD_SUFFIX.length)
      methodNamesWithYield.contains(lookupMethodName)
    } else {
      false
    }

    if (isYieldMethod) {
      /*
       * This is a yield block. Create a fake method out of it. The yield call will be a call to the yield block
       */
      astForBlockFunction(
        ctx.block().compoundStatement.statements(),
        ctx.block().blockParameter,
        blockName,
        line(ctx).head,
        lineEnd(ctx).head,
        column(ctx).head,
        columnEnd(ctx).head
      )
    } else {
      val blockAst = astForBlock(ctx.block())
      // this is expected to be a call node
      val callNode = methodIdAst.head.nodes.head.asInstanceOf[NewCall]
      Seq(callAst(callNode, blockAst))
    }
  }

  private def astForInvocationWithParenthesesPrimaryContext(ctx: InvocationWithParenthesesPrimaryContext): Seq[Ast] = {
    val methodIdAst = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    val parenAst    = astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())
    val callNode    = methodIdAst.head.nodes.filter(_.isInstanceOf[NewCall]).head.asInstanceOf[NewCall]
    callNode.name(resolveAlias(callNode.name))

    if (ctx.block() != null) {
      val isYieldMethod = if (callNode.name.endsWith(Defines.YIELD_SUFFIX)) {
        val lookupMethodName = callNode.name.take(callNode.name.length - Defines.YIELD_SUFFIX.length)
        methodNamesWithYield.contains(lookupMethodName)
      } else {
        false
      }
      if (isYieldMethod) {
        val methAst = astForBlock(ctx.block(), Some(callNode.name))
        methAst
          .collectFirst { case x: Ast if x.root.isDefined && x.root.get.isInstanceOf[NewMethod] => x }
          .foreach(blockMethods.addOne)
        Seq(callAst(callNode, parenAst))
      } else if (callNode.name == Defines.DEFINE_METHOD) {
        parenAst.headOption
          .flatMap(_.root)
          .collect { case x: AstNodeNew => stripQuotes(x.code).stripPrefix(":") }
          .map(methodName => astForBlock(ctx.block(), Option(methodName)))
          .getOrElse(Seq.empty)
      } else {
        val blockAst = astForBlock(ctx.block())
        Seq(callAst(callNode, parenAst ++ blockAst))
      }
    } else
      Seq(callAst(callNode, parenAst))
  }

  def astForCallNode(ctx: ParserRuleContext, code: String, isYieldBlock: Boolean = false): Ast = {
    val name = if (isYieldBlock) {
      s"${resolveAlias(text(ctx))}${Defines.YIELD_SUFFIX}"
    } else {
      val calleeName = resolveAlias(text(ctx))
      // Add the call name to the global builtIn callNames set
      if (isBuiltin(calleeName)) builtInCallNames.add(calleeName)
      calleeName
    }

    callAst(callNode(ctx, code, name, DynamicCallUnknownFullName, DispatchTypes.STATIC_DISPATCH))
  }

  private def astForMethodOnlyIdentifier(ctx: MethodOnlyIdentifierContext): Seq[Ast] = {
    if (ctx.LOCAL_VARIABLE_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, text(ctx)))
    } else if (ctx.CONSTANT_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, text(ctx)))
    } else if (ctx.keyword() != null) {
      Seq(astForCallNode(ctx, ctx.keyword().getText))
    } else {
      Seq(Ast())
    }
  }

  def astForMethodIdentifierContext(ctx: MethodIdentifierContext, code: String): Seq[Ast] = {
    // the local/const identifiers are definitely method names
    if (ctx.methodOnlyIdentifier() != null) {
      astForMethodOnlyIdentifier(ctx.methodOnlyIdentifier())
    } else if (ctx.LOCAL_VARIABLE_IDENTIFIER() != null) {
      val localVar  = ctx.LOCAL_VARIABLE_IDENTIFIER()
      val varSymbol = localVar.getSymbol
      Seq(astForCallNode(ctx, code, methodNamesWithYield.contains(varSymbol.getText)))
    } else if (ctx.CONSTANT_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, code))
    } else {
      Seq.empty
    }
  }

  def astForRescueClauseContext(ctx: RescueClauseContext): Ast = {
    val asts = ListBuffer.empty[Ast]

    if (ctx.exceptionClass() != null) {
      val exceptionClass = ctx.exceptionClass()

      if (exceptionClass.expression() != null) {
        asts.addAll(astForExpressionContext(exceptionClass.expression()))
      } else {
        asts.addAll(astForMultipleRightHandSideContext(exceptionClass.multipleRightHandSide()))
      }
    }

    if (ctx.exceptionVariableAssignment() != null) {
      asts.addAll(astForSingleLeftHandSideContext(ctx.exceptionVariableAssignment().singleLeftHandSide()))
    }

    asts.addAll(astForCompoundStatement(ctx.thenClause().compoundStatement(), false))
    blockAst(blockNode(ctx), asts.toList)
  }

  private def astForSimpleScopedConstantReferencePrimaryContext(
    ctx: SimpleScopedConstantReferencePrimaryContext
  ): Seq[Ast] = {
    val localVar  = ctx.CONSTANT_IDENTIFIER
    val varSymbol = localVar.getSymbol
    val node      = createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))

    val operatorName = getOperatorName(ctx.COLON2.getSymbol)
    val callNode     = createOpCall(ctx.COLON2, operatorName, text(ctx))

    Seq(callAst(callNode, Seq(Ast(node))))
  }

  private def astForCommandWithDoBlockContext(ctx: CommandWithDoBlockContext): Seq[Ast] = ctx match {
    case ctx: ArgsAndDoBlockCommandWithDoBlockContext =>
      val argsAsts   = astForArguments(ctx.argumentsWithoutParentheses().arguments())
      val doBlockAst = astForDoBlock(ctx.doBlock())
      argsAsts ++ doBlockAst
    case ctx: RubyParser.ArgsAndDoBlockAndMethodIdCommandWithDoBlockContext =>
      val methodIdAsts = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
      methodIdAsts.headOption.flatMap(_.root) match
        case Some(methodIdRoot: NewCall) if methodIdRoot.name == Defines.DEFINE_METHOD =>
          ctx.argumentsWithoutParentheses.arguments.argument.asScala.headOption
            .map { methodArg =>
              // TODO: methodArg will name the method, but this could be an identifier or even a string concatenation
              //  which is not assumed below
              val methodName = stripQuotes(methodArg.getText).stripPrefix(":")
              astForDoBlock(ctx.doBlock(), Option(methodName))
            }
            .getOrElse(Seq.empty)
        case _ =>
          val argsAsts    = astForArguments(ctx.argumentsWithoutParentheses().arguments())
          val doBlockAsts = astForDoBlock(ctx.doBlock())
          methodIdAsts ++ argsAsts ++ doBlockAsts
    case ctx: RubyParser.PrimaryMethodArgsDoBlockCommandWithDoBlockContext =>
      val argsAsts       = astForArguments(ctx.argumentsWithoutParentheses().arguments())
      val doBlockAsts    = astForDoBlock(ctx.doBlock())
      val methodNameAsts = astForMethodNameContext(ctx.methodName())
      val primaryAsts    = astForPrimaryContext(ctx.primary())
      primaryAsts ++ methodNameAsts ++ argsAsts ++ doBlockAsts
    case _ =>
      logger.error(s"astForCommandWithDoBlockContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  private def astForChainedCommandWithDoBlockContext(ctx: ChainedCommandWithDoBlockContext): Seq[Ast] = {
    val cmdAsts   = astForCommandWithDoBlockContext(ctx.commandWithDoBlock)
    val mNameAsts = ctx.methodName.asScala.flatMap(astForMethodNameContext).toSeq
    val apAsts = ctx
      .argumentsWithParentheses()
      .asScala
      .flatMap(astForArgumentsWithParenthesesContext)
      .toSeq
    cmdAsts ++ mNameAsts ++ apAsts
  }

  protected def astForArgumentsWithParenthesesContext(ctx: ArgumentsWithParenthesesContext): Seq[Ast] = ctx match {
    case _: BlankArgsArgumentsWithParenthesesContext  => Seq.empty
    case ctx: ArgsOnlyArgumentsWithParenthesesContext => astForArguments(ctx.arguments)
    case ctx: ExpressionsAndChainedCommandWithDoBlockArgumentsWithParenthesesContext =>
      val expAsts = ctx.expressions.expression.asScala
        .flatMap(astForExpressionContext)
        .toSeq
      val ccDoBlock = astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock)
      expAsts ++ ccDoBlock
    case ctx: ChainedCommandWithDoBlockOnlyArgumentsWithParenthesesContext =>
      astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock)
    case _ =>
      logger.error(s"astForArgumentsWithParenthesesContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  private def astForBlockParametersContext(ctx: BlockParametersContext): Seq[Ast] =
    if (ctx.singleLeftHandSide != null) {
      astForSingleLeftHandSideContext(ctx.singleLeftHandSide)
    } else if (ctx.multipleLeftHandSide != null) {
      astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide)
    } else {
      Seq.empty
    }

  protected def astForBlockParameterContext(ctx: BlockParameterContext): Seq[Ast] =
    if (ctx.blockParameters != null) {
      astForBlockParametersContext(ctx.blockParameters)
    } else {
      Seq.empty
    }

  def astForAssociationContext(ctx: AssociationContext): Seq[Ast] = {
    val terminalNode = Option(ctx.COLON).getOrElse(ctx.EQGT)
    val operatorText = getOperatorName(terminalNode.getSymbol)
    val expressions  = ctx.expression.asScala

    val (callArgs, otherAst) =
      (Option(ctx.keyword) match {
        case Some(ctxKeyword) =>
          val expr1Ast  = astForCallNode(ctx, ctxKeyword.getText)
          val expr2Asts = astForExpressionContext(expressions.head)
          Seq(expr1Ast) ++ expr2Asts
        case None =>
          val expr1Asts = astForExpressionContext(expressions.head)
          val expr2Asts = expressions.lift(1).flatMap(astForExpressionContext)
          expr1Asts ++ expr2Asts
      }).partitionExprAst

    val callNode = createOpCall(terminalNode, operatorText, text(ctx))
    otherAst ++ Seq(callAst(callNode, callArgs))
  }

  private def astForAssociationsContext(ctx: AssociationsContext): Seq[Ast] = {
    ctx.association.asScala
      .flatMap(astForAssociationContext)
      .toSeq
  }

}
