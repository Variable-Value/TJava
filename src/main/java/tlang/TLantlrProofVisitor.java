package tlang;

import java.util.Map;
import java.util.Optional;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import tlang.KnowledgeBase.ProofResult;
import tlang.Scope.VarInfo;
import tlang.TLantlrParser.InitializedVariableContext;
import tlang.TLantlrParser.MeansStmtContext;
import tlang.TLantlrParser.T_blockStatementContext;
import tlang.TLantlrParser.T_expressionContext;
import tlang.TLantlrParser.T_expressionDetailContext;
import tlang.TLantlrParser.T_localVariableDeclarationContext;
import tlang.TLantlrParser.T_methodDeclarationContext;
import tlang.TLantlrParser.T_parExpressionContext;
import tlang.TLantlrParser.T_primaryContext;
import tlang.TLantlrParser.T_statementContext;
import tlang.TLantlrParser.T_typeDeclarationContext;
import static tlang.TUtil.*;
import static tlang.TLantlrParser.*;
import static tlang.KnowledgeBase.*;

/** Check all constraints for consistency and check that all deductions are supported. This is the
 * final pass before the compiler generates Java code. Eventually, the proof pass will be rather
 * complicated, including correctness of initialization of the static object, correctness of the
 * static methods, correctness of instance initialization, including all constructors, and
 * correctness of all methods.
 *
 * There are 4 passes:
 * <ol>
 * <li>Collect all field types and static field values, which was done in the FieldVisitor class
 * <li>Check static validity, including static initialization blocks, passing the static object
 * KnowledgeBase to the following proof step.
 * <li>Collect instance field initializers and initialization blocks, passing the instance
 * initialization KnowledgeBase for constructors, and also the instance object KnowledgeBase for
 * methods, to the following proof step.
 * <li>Prove instance validity, including both methods and constructors.
 * </ol>
 * <p>
 * References like (Deransart, et al., p.236) refer to
 * <p>
 * <cite>Prolog: The Standard:Reference Manual</cite>, P. Deransart, A. Ed-Dbali, L. Cervoni,
 * Springer-Verlog, 1996.
 * <p>
 * TODO: All class fields must be non-null by the end of a constructor unless they were declared
 * with the <code>optional</code> modifier. */
class TLantlrProofVisitor extends RewriteVisitor {

private static final String prover = "Prover";
/* TODO: remove the following CollectingMsgListener from the knowledgeBase and have the calling
 * program decide what to do. */

/** The list of the program's errors generated by the compiler. Here it is used to report to the
 * users a failure in an attempt to prove a statement. */
private CollectingMsgListener errors;
private static char prologDecoratorChar = '^';
private static RewriteVisitor latestProofVisitor;

/** Contains a logical representation of the state of a program. A child KnowledgeBase is created
 * for each scope in which something might need to be proven. */
private KnowledgeBase kb = new KnowledgeBase();


public TLantlrProofVisitor(
  TokenStream tokenStream,
  Map<RuleContext, Scope> ctxToScope,
  CollectingMsgListener errors)
{
  super(tokenStream, ctxToScope);
  this.errors = errors;
}

/** All <code>axiom, constraint, conjecture,</code> and <code>given</code> statements must be
 * consistent and, with the code, must support the <code>theorem, lemma,</code> and
 * <code>means</code> statements.
 *
 * @param  parseTree   of the code to be proven
 * @param  tokenStream that was used to generate the parseTree
 * @param  ctxToScope  map from a parse context to all scope information, especially variables
 * @param  errors      collects all error messages
 * @return             a record of the prolog calls that were made to the prover */
public static String proveCorrectness(
  ParseTree parseTree,
  TokenStream tokenStream,
  Map<RuleContext, Scope> ctxToScope,
  CollectingMsgListener errors)
{
  latestProofVisitor = new TLantlrProofVisitor(tokenStream, ctxToScope, errors);
  latestProofVisitor.visit(parseTree);
  return getProlog();
}

public static String getProlog() {
  return latestProofVisitor.rewriter.getText();
}


//***** Visit the Nodes that call the prover and rewrite the code into Prolog *******


/** Override the visit to this node in order to put all the calls to visit varieties of value-name
 * decoration into the same class and near one another.
 * @param  ctx The parse tree, which is the single, leaf, node for the undecorated value name that
 *               contains the value-name token.
 * @return     null */
@Override
public
        Void visitT_UndecoratedIdentifier(T_UndecoratedIdentifierContext ctx)
{
  return null;
}

/** Translate a pre-decorated value name to its Prolog form and substitute it in place. The
 * character <code>^</code> is substituted for the decorator, a scope name is prefixed using a dot
 * separator, and the whole name is enclosed in single quotes. For example, <code>'xyx</code> is
 * transformed to <code>'^xyz'</code> and if the variable <code>xyz</code> is an instance field, the
 * final transformation will be <code>'this.^xyz'</code>. Value names for local variables that are
 * at the top level of an executable component, e.g., a method, are not prefixed with a scope.
 * @param  valueNameCtx The parse tree, which is the single, leaf, node for the pre-decorated value
 *                        name that contains the value-name token.
 * @return              null */
@Override
public Void visitT_PreValueName(T_PreValueNameContext valueNameCtx) {
  final String variableName = rewriter.source(valueNameCtx).substring(1); // e.g., 'xxx -> xxx
  final String scopePrefix = getScopePrefix(variableName);
  final String prologName = "'" + scopePrefix + "^" + variableName + "'"; // e.g., 'this.^xxx'
  rewriter.substituteText(valueNameCtx, prologName);
  return null;
}

/** Translate a mid-decorated value name to its Prolog form and substitute it in place. The
 * character <code>^</code> is substituted for the decorator, a scope name is prefixed using a dot
 * separator, and the whole name is enclosed in single quotes. For example, <code>abc'xyx</code> is
 * transformed to <code>'abc^xyz'</code> and if the variable <code>abc</code> is an instance field,
 * the final transformation will be <code>'this.abc^xyz'</code>. Value names for local variables
 * that are at the top level of an executable component, e.g., a method, are not prefixed with a
 * scope.
 * @param  valueNameCtx The parse tree, which is the single, leaf, node for the mid-decorated value
 *                        name that contains the value-name token.
 * @return              null */
@Override
public Void visitT_MidValueName(T_MidValueNameContext valueNameCtx) {
  final String valueName = rewriter.source(valueNameCtx);         // example : varName'xxx
  final String[] n = valueName.split(decoratorString);                 // { varName,xxx }
  final String prologName = "'" + getScopePrefix(n[0]) + n[0] + "^" + n[1] + "'";
                        // 'this.varName^xxx'
  rewriter.substituteText(valueNameCtx, prologName);
  return null;
}

/** Translate a post-decorated value name to its Prolog form, in place. The character <code>^</code>
 * is substituted for the decorator, a scope name is prefixed using a dot separator, and the whole
 * name is enclosed in single quotes. For example, <code>abc'</code> is transformed to
 * <code>'abc^'</code> and if the variable <code>abc</code> is an instance field, the final
 * transformation will be <code>'this.abc^'</code>. Value names for local variables that are at the
 * top level of an executable component, e.g., a method, are not prefixed with a scope.
 * <p>
 * {@inheritDoc}
 * @param  valueNameCtx The parse tree, which is the single, leaf, node for the post-decorated value
 *                        name that contains the value-name token.
 * @return              null */
@Override
public Void visitT_PostValueName(T_PostValueNameContext valueNameCtx) {
  final String valueName = rewriter.source(valueNameCtx);
  final String variableName = valueName.substring(0, valueName.length() - 1);
  rewriter.substituteText(valueNameCtx, "'" + getScopePrefix(variableName) + variableName + "^'");
    // "'" is part of the prolog name, not a decorator
  return null;
}

/** Translate a Java literal into the corresponding prover literal.
 * <p>
 * For the <code>FloatingPointLiteral</code>, change something like .25 to 0.25, with a leading
 * zero. (Deransart, et al., p.236)
 * @return null required by implementation */
@Override
public Void visitT_literal(T_literalContext literalCtx) {
  visitChildren(literalCtx);

  TerminalNode terminalNode = literalCtx.FloatingPointLiteral();
  if (terminalNode != null) {
    String numericText = terminalNode.getText();
    if (numericText.startsWith("."))
      rewriter.substituteText(literalCtx, "0" + numericText);
  }
  // TODO: change 123E-4 to 0.0123 and .2E12 to 200000000000

  return null;
}

/**
 * <p>
 * {@inheritDoc} */
@Override
public Void visitInitializedVariable(InitializedVariableContext ctx) {
  visitChildren(ctx);

  String translatedOp = needsEquivalenceForBooleanTarget(ctx) ? " === " : " = ";
  rewriter.replace(ctx.op, translatedOp);
  kb.assume(rewriter.source(ctx));
  return null;
}

/** Submit the assignment to the prover. TODO: provide the useful type information for the new value
 * name that is being created.
 * <p>
 * {@inheritDoc} */
@Override
public Void visitAssignStmt(AssignStmtContext ctx) {
  visitChildren(ctx);

  String rhs = rewriter.source(ctx.t_assignable());
  String op = isBooleanIdentifier(ctx.t_assignable().t_identifier())
              ? "===" : " = ";
  String lhs = parenthesize(rewriter.source(ctx.t_expression()));
  String src = parenthesize(rhs + op + lhs);
  rewriter.substituteText(ctx, src);
  kb.assume(src);
  return null;
}

private boolean needsEquivalenceForBooleanTarget(InitializedVariableContext ctx) {
  var targetCtx = ctx.t_initializedVariableDeclaratorId().t_idDeclaration().t_identifier();
  return isBooleanIdentifier(targetCtx);
}

private boolean isBooleanIdentifier(T_identifierContext targetCtx) {
  String targetVarName = TUtil.variableName(targetCtx.getText());
  String varType = currentScope.getExistingVarInfo(targetVarName).getType();
  return varType.equals("boolean") || varType.equals("Boolean");
}

/**
 * If there is a return value, allow the programmer to use either decorated or undecorated
 * value, e.g., <code>return'</code> or <code>return</code> in logic statements. This means that the
 * Context Checker must refuse the use of both.
 */
@Override public Void visitReturnStmt(TLantlrParser.ReturnStmtContext ctx) {
  visitChildren(ctx);

  String expression = ctx.t_expression().getText();
  String returnTranslation = ctx.t_expression().isEmpty() ? "true" : returnExpression(expression);
  rewriter.substituteText(ctx, returnTranslation);
  return null;
}

/**
 * Gives the predicate needed to equate the returned expression to the value-name that represents
 * the returned value, which may be either <code>return'</code> or <code>return</code>
 *
 * Note that the single quote marks "'" are part of the prolog name, not decorators.
 */
private String returnExpression(String returnedExpression) {
  String decoratedReturn = "'" + getScopePrefix("return") + "return^'";
  if (TCompiler.isRequiringDecoratedFinalValue)
    return    parenthesize(decoratedReturn +" = "+ returnedExpression);
  else
    return    parenthesize(decoratedReturn +" = "+ returnedExpression)
      + and + parenthesize(           "return = "+ returnedExpression);
}

@Override public Void visitEmptyStmt(TLantlrParser.EmptyStmtContext ctx) {
  rewriter.substituteText(ctx, "true");
  return null;
}

@Override public Void visitMultiplicativeExpr(TLantlrParser.MultiplicativeExprContext ctx) {
  visitChildren(ctx);

  rewriter.substituteText(ctx, parenthesize(rewriter.source(ctx)));
  return null;
}

@Override public Void visitAdditiveExpr(TLantlrParser.AdditiveExprContext ctx) {
  visitChildren(ctx);

  rewriter.substituteText(ctx, parenthesize(rewriter.source(ctx)));
  return null;
}



/**
 * A method declaration has a background scope for a parent in order to hold all
 * the higher scope fields.
 */
@Override public Void visitT_methodDeclaration(T_methodDeclarationContext ctx) {
  withChildOfKb( () -> {super.visitT_methodDeclaration(ctx);} );
  return null;
}

/** Translate a block of statements into the meaning of its statements, changing the surrounding
 * braces to parentheses. Loop from the bottom up, stopping with the latest means-statement that was
 * issued, which summarizes everything needed from the code above it in this block. */
/* TODO: Progress from the top down. Every status statement will need to be proven. At each
 * means-statement, discard the preceeding statements, but keep variable type info. */
/* TODO: after the means-statement is encountered, keep looking for variable declarations to collect
 * type information for all valueNames that occur in the meaningful code. */
@Override public Void visitT_block(T_blockContext ctx) {
  withChildOfKb(() ->
    withChildScopeForCtx(ctx, () -> visitChildren(ctx))
  );
  boolean statementsAreActive = true; // so far
  String types = "true";
  String meaning = "true";
  for (int i = ctx.t_blockStatement().size()-1; i >= 0; i-- ) {
    T_blockStatementContext bStCtx = ctx.t_blockStatement(i);
    T_statementContext statement = bStCtx.t_statement();
    if (statement != null) {
      if (statementsAreActive) {
        if (statement instanceof MeansStmtContext) {
          statementsAreActive = false;
          meaning += and + rewriter.source(getExpressionCtx(statement));
        } else {
          meaning += and + rewriter.source(statement);
        }
      }
    } else {
      T_localVariableDeclarationContext localDeclaration = bStCtx.t_localVariableDeclaration();
      if (localDeclaration != null) {
        String type = localDeclaration.t_type().getText();
        for (T_variableDeclaratorContext declarator : localDeclaration.t_variableDeclarator()) {
          if (declarator instanceof UninitializedVariableContext) {
            var uninitStatement = (UninitializedVariableContext)declarator;
            String valueName = rewriter.source(uninitStatement.t_uninitializedVariableDeclaratorId());
            types += and + " type("+ type +","+ valueName +")";
          } else { // initialization instanceof InitializedVariableContext
            InitializedVariableContext initStatement = (InitializedVariableContext)declarator;
            String valueName = rewriter.source(initStatement.t_initializedVariableDeclaratorId());
            types += and + " type("+ type +","+ valueName +")";
            if (statementsAreActive)
              meaning += and + parenthesize(rewriter.source(initStatement));
          }
        }
      } else {
        T_typeDeclarationContext localType = bStCtx.t_typeDeclaration();
        //if (localType != null) // localType cannot be null because of syntax
        // TODO: After programming type definition, make sure that local type definition works, too.
        //if (statementsAreActive) {
        //  meaning += and + ....
        //}
      }
    }
  }

  rewriter.substituteText(ctx, meaning);
  kb.assume(meaning);

  return null;
}

private T_expressionContext getExpressionCtx(T_statementContext statement) {
  return ((MeansStmtContext)statement).t_means().t_expression();
}

@Override public Void visitWhileStmt(TLantlrParser.WhileStmtContext ctx) {
  visitChildren(ctx);

  String condition = rewriter.source(ctx.t_parExpression());
  String body = parenthesize(rewriter.source(ctx.t_statement()));
  rewriter.substituteText(ctx, parenthesize(condition + and + body )); // rewriter.source(ctx)));
  return null;
}


/** Translate if-statement to logic. */
@Override
public Void visitIfStmt(IfStmtContext ctx) {
  String condition = translateCondition(ctx.t_parExpression());
  String thenMeaning = checkBranch(condition, ctx.t_statement(0));

  String elseMeaning = "";
  T_statementContext elseContext = ctx.t_statement(1);
  if (elseContext == null)
    elseMeaning = negate(condition);
  else
    elseMeaning = checkBranch(negate(condition), elseContext);

  rewriter.substituteText(ctx, parenthesize(thenMeaning + or + elseMeaning));
  kb.assume(rewriter.source(ctx));
  return null;
}

private String negate(String condition) {
  return parenthesize(not + condition);
}

private String translateCondition(T_parExpressionContext parenthesizedExpressionCtx) {
  visit(parenthesizedExpressionCtx);
  String condition = rewriter.source(parenthesizedExpressionCtx);
  return condition;
}

/** A branch is a scope, but since it is also a single statement, it doesn't require the
 * paraphernalia that a scope normally requires. */
private String checkBranch(String condition, T_statementContext branchCtx) {
  withChildOfKb(() -> {
    kb.assume(condition);
    visit(branchCtx);
  });
  return parenthesize(condition + and + rewriter.source(branchCtx));
}

@Override public Void visitT_expression(TLantlrParser.T_expressionContext ctx) {
  visitChildren(ctx);

  //TODO do we need this:  rewriter.substituteText(ctx, parenthesize(rewriter.source(ctx)));
  return null;
}


/** Translate <code>!</code> to the provers negation <code>-</code> */
@Override
public Void visitNotExpr(NotExprContext ctx) {
  visitChildren(ctx);

  rewriter.replace(ctx.start, "-");
  return null;
}

/** Parenthesize the relational expression and translate the operators to appropriate prover
 * operators. */
@Override
public Void visitConjRelationExpr(TLantlrParser.ConjRelationExprContext ctx) {
  visitChildren(ctx);

  translateOps(ctx);
  rewriter.substituteText(ctx, parenthesize(rewriter.source(ctx)));
  return null;
}

//@formatter:off
/**
 * Translate the operators to appropriate prover operators. If the expressions are boolean, use the
 * provers logical operators.
 *
 * <table>
 *   <tr><th>Java  <th>Prover
 *   <tr><td>&lt;  <td>&lt;
 *   <tr><td>&lt;= <td>=&lt;
 *   <tr><td>=     <td>=  (=== for boolean)
 *   <tr><td>!=    <td>#= (=#= for boolean)
 *   <tr><td>&gt;= <td>&gt;=
 *   <tr><td>&gt;  <td>&gt;
 * </table>
 *
 * @param ctx
 */
//@formatter:on
private void translateOps(ConjRelationExprContext ctx) {
  String operator = ctx.op.getText();
  if ("<=".equals(operator))
    rewriter.replace(ctx.op, "=<");
  if ("=".equals(operator))
    rewriter.replace(ctx.op, hasBooleanTerms(ctx.t_expressionDetail(0)) ? "===" : " = ");
  if ("!=".equals(operator))
    rewriter.replace(ctx.op, hasBooleanTerms(ctx.t_expressionDetail(0)) ? "=#=" : "#=");
}

private boolean hasBooleanTerms(T_expressionDetailContext ctx) {
  if (ctx instanceof AndExprContext)
    return true;
  if (ctx instanceof ConjRelationExprContext)
    return true;
  if (ctx instanceof PrimaryExprContext)
    return isBooleanPrimary(((PrimaryExprContext)ctx).t_primary());
  if (ctx instanceof NotExprContext)
    return true;
//    if (ctx instanceof FuncCallExprContext) {
//      // TODO: does this function return a boolean?
//    }
  if (ctx instanceof DotExprContext)
    return isBooleanDotExpr((DotExprContext)ctx);
  if (ctx instanceof ConditionalExprContext) {  // e(0) ? e(1) : e(2)
    ConditionalExprContext ceCtx = (ConditionalExprContext)ctx;
    return hasBooleanTerms(ceCtx.t_expressionDetail(1)); // ||
                                                         // hasBooleanTerms(ceCtx.t_expressionDetail(2));
  }
//    if (ctx instanceof DotExplicitGenericExprContext) { /* TODO: returns boolean? */ }
  if (ctx instanceof InstanceOfExprContext)
    return true;
  if (ctx instanceof OrExprContext)
    return true;
  if (ctx instanceof ConditionalOrExprContext)
    return true;
  if (ctx instanceof ArrayExprContext) {
    ArrayExprContext aeCtx = (ArrayExprContext)ctx;
    if (hasBooleanTerms(aeCtx.t_expressionDetail(0)))
      return true;
  }
  if (ctx instanceof ExclusiveOrExprContext)
    return true;
//    if (ctx instanceof NewExprContext) { /* TODO: add this; however, new Boolean(true) is deprecated */ }
  if (ctx instanceof ConditionalAndExprContext)
    return true;
//    if (ctx instanceof TypeCastExprContext)  { /* TODO: check for casting boolean to Boolean (deprecated) */ }
  if (ctx instanceof ConditionalAndExprContext)
    return true;
  // OTHERWISE
  return false;
}

public boolean isBooleanDotExpr(DotExprContext ctx) {
  if ("this".equals(rewriter.source(ctx.t_expressionDetail()))
      && isBooleanIdentifier(ctx.t_identifier()))
    return true;
  // TODO: return true if other (non-this) object component identifier is boolean
  // otherwise
  return false;
}

/** Check all possible booleans in the parse rule t_primary in the TLantlr.g4 grammar
 * as of 2019 Jan 16
 */
public boolean isBooleanPrimary(T_primaryContext ctx) {
  if (ctx.getChild(0) instanceof T_parExpressionContext)
    return hasBooleanTerms(((T_parExpressionContext)ctx.getChild(0)).t_expression()
                                                                    .t_expressionDetail());
  if (ctx.t_identifier() != null)
    return isBooleanIdentifier(ctx.t_identifier());

  return(ctx.getText().equals("true") || ctx.getText().equals("false"));
}

/** Replace the Java OR (|) with the prover OR (\/).
 * <p>
 * {@inheritDoc} */
@Override
public Void visitOrExpr(OrExprContext ctx) {
  visitChildren(ctx);

  rewriter.replace(binaryOperatorToken(ctx), or);
  return null;
}

/** Replace the Java AND (&) with the prover AND (/\).
 * <p>
 * {@inheritDoc} */
@Override
public Void visitAndExpr(AndExprContext ctx) {
  visitChildren(ctx);

  rewriter.replace(binaryOperatorToken(ctx), and);
  return null;
}

/** Replace the Java conditional OR (||) with the prover OR (\/).
 * <p>
 * {@inheritDoc} */
@Override
public Void visitConditionalOrExpr(ConditionalOrExprContext ctx) {
  visitChildren(ctx);

  rewriter.replace(binaryOperatorToken(ctx), or);
  return null;
}

/** Replace the Java conditional AND (&&) with the prover AND (/\).
 * <p>
 * {@inheritDoc} */
@Override
public Void visitConditionalAndExpr(ConditionalAndExprContext ctx) {
  visitChildren(ctx);

  rewriter.replace(binaryOperatorToken(ctx), and);
  return null;
}

private Token binaryOperatorToken(ParseTree pt) {
  return (Token)pt.getChild(1).getPayload();
}

/** The operators ===, ==>, and <== are the same as those used in the prover, but =!= must be
 * replaced with =#=. */
@Override
public Void visitConjunctiveBoolExpr(ConjunctiveBoolExprContext ctx) {
  visitChildren(ctx);

  if ("=!=".equals(ctx.op)) {
    rewriter.replace(ctx.op, "=#=");
  }
  /*TODO: Look for child conjunctive boolean expression and duplicate terms to simulate conjunctive
   * operators. See grammar for details but watch out for parentheses.
   */
  // rewriter.substituteText(ctx, "( "+rewriter.source(ctx)+" )");
  return null;
}


/** Translate the expression of the means-statement into the KnowledgeBase language and submit the
 * means statement to the {@link KnowledgeBase} for proof, then substitute the <code>means</code>
 * expression for all the preceding assumptions, preserving the type information for any value names
 * that occur in the <code>means</code> statement.
 * <p>
 * {@inheritDoc}
 * @return a null */
@Override
public Void visitT_means(T_meansContext ctx) {
  visitChildren(ctx); // rewrite code into the KnowledgeBase language

  T_expressionDetailContext predicate = ctx.t_expression().t_expressionDetail();
  String meansStatementForProver = prologCode(predicate);
  ProofResult result = kb.substituteIfProven(meansStatementForProver);
  if ( result != ProofResult.provenTrue)
    result = proveEachConjunct(predicate);
  rewriter.substituteText(ctx.t_expression(), meansStatementForProver);
  return null;
}

private ProofResult proveEachConjunct(T_expressionDetailContext conjunction) {
  conjunction = removeAnyParentheses(conjunction);
  if (isSingleConjunct(conjunction)) {
    ProofResult result = kb.assumeIfProven(prologCode(conjunction));
    reportAnyError(conjunction, result);
    return result;
  }

  for (ParseTree child : conjunction.children) {
    if (child instanceof T_expressionDetailContext) {
      T_expressionDetailContext expr = (T_expressionDetailContext)child;
      ProofResult conjunctResult = proveEachConjunct(expr);
      if (conjunctResult != ProofResult.provenTrue)
        return conjunctResult;
    }
  }
  return ProofResult.provenTrue;
}

private T_expressionDetailContext removeAnyParentheses(T_expressionDetailContext conjunction) {
  if ( ! (conjunction instanceof PrimaryExprContext))
    return conjunction;

  final var primary = ((PrimaryExprContext)conjunction).t_primary();
  final var parens = primary.t_parExpression();
  if (parens == null)
    return conjunction;

  T_expressionContext theExpression = parens.t_expression();
  final var innerExpression = theExpression.t_expressionDetail();
  return removeAnyParentheses(innerExpression);
}

private boolean isSingleConjunct(T_expressionDetailContext expressionDetail) {
  return !isConjunction(expressionDetail);
}

private boolean isConjunction(ParseTree expressionDetail) {
  return expressionDetail instanceof ConditionalAndExprContext
         || expressionDetail instanceof AndExprContext;
}

private void reportAnyError(ParserRuleContext ctx, ProofResult result) {
  if (result == ProofResult.provenTrue)
    return;

  String msg = null;
  if (result == ProofResult.unsupported)
    msg = "The code does not support the proof of the statement: " + rewriter.originalSource(ctx);
  else if (result == ProofResult.reachedLimit)
    msg = "The prover reached an internal limit. Consider adding a lemma to help prove "
          + "the statement: \n    " + rewriter.originalSource(ctx);
  errors.collectError(prover, ctx.getStart(), msg);
}

/** @param node
 * @return */
private String prologCode(ParseTree node) {
  String translated = expandForall(rewriter.source(node));
  return translated.replaceAll("//", "%");
}

/** Search for variables that are bound by a <code>forall</code> statement and add the expanded type
 * information for the variables inside the scope of the <code>forall</code>, that is, inside the
 * quantified statement) so that it will be available for use in the proof. Conjoin the "useful"
 * type constraints at the beginning of the scope of the bound variable and the "deep" constraints
 * at the end of the scope of the bound variable.
 * @param  statement the statement to be proven from a <code>means</code> statement
 * @return           the modified statement */
private String expandForall(String statement) {
  // TODO Expand forall statements for Prolog by adding type information for each forall variable
  return statement;
}

/* ************************ Helper methods ************************************/

/** Parenthesize the string */
private String parenthesize(String expression) {
  return "(" + expression + ")";
}

/** Get the name of the scope with a following dot separator, ready for prefixing to a prolog
 * variable name. For instance, an object-instance field returns "this." and a local variable that
 * is declared at the top level of a method returns "".
 * @param  variableName
 * @return              scope name followed by a dot separator */
private String getScopePrefix(final String variableName) {
  final Optional<VarInfo> info = currentScope.getOptionalExistingVarInfo(variableName);
  return info.map(v -> v.getScopeWhereDeclared().getLabel() + ".")
             .orElse("");
}

/** The full type name will need to include the package where it is defined unless it is a
 * primitive, but for now we just return the type as given in the code.
 * @param  idType
 * @return */
private String typeFullName(String idType) {
  // TODO look up type in information from imports or package
  return idType;
}

/** Extract the full variable name, including any scope prefix, from the value name, keeping the
 * single quotes required for general Prolog atom names.
 *
 * @param  val The Prolog version of the value name, including single quotes and any scope prefix.
 * @return     The variable name constructed from the <code>val</code> */
private String varName(String val) {
  final int pos = val.indexOf(prologDecoratorChar);
  if (pos == -1)
    return val;                               // return 'abc' for 'abc'
  else if (pos == 1)
    return "'" + val.substring(2);            // return 'abc' for '^abc'
  else
    return val.substring(0, pos) + "'"; // return 'abc' for 'abc^' or 'abc^de'
}

/** Use the Java execution stack as an implicit stack for knowledgebases  */
private void withChildOfKb(Runnable acceptFunction) {
  KnowledgeBase parentKb = kb;
  kb = new KnowledgeBase(parentKb);  // create child kb
  acceptFunction.run();              // use child kb
  kb = parentKb;                     // restore parent kb
}

} // end class