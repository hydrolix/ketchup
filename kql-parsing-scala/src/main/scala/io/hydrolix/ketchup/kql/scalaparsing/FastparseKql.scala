package io.hydrolix.ketchup.kql.scalaparsing

import fastparse.NoWhitespace._
import fastparse._
import io.hydrolix.ketchup.model.expr._
import io.hydrolix.ketchup.model.kql._

import java.lang.{Boolean => jBoolean}
import scala.jdk.CollectionConverters._

// This was originally ported manually from
// https://github.com/opensearch-project/OpenSearch-Dashboards/blob/d7004dc5b0392477fdd54ac66b29d231975a173b/src/plugins/data/common/opensearch_query/kuery/ast/kuery.peg
// but has suffered several hacky edits since then.
//
// TODO replace this with something more traditional with a separate grammar, generated AST, etc.
object FastparseKql extends KqlParser[Parsed.Failure] {
  override def parseKql(s: String): arrow.core.Either[Parsed.Failure, Expr[jBoolean]] = {
    fastparse.parse(s, pProgram(_)) match {
      case fail: Parsed.Failure => new arrow.core.Either.Left(fail).asInstanceOf[arrow.core.Either[Parsed.Failure, Expr[jBoolean]]]
      case success: Parsed.Success[_] => new arrow.core.Either.Right(success.value).asInstanceOf[arrow.core.Either[Parsed.Failure, Expr[jBoolean]]]
    }
  }

  private val numR = """^(-?[1-9]+\d*([.]\d+)?)$|^(-?0[.]\d*[1-9]+)$|^0$|^0.0$|^[.]\d+$""".r

  implicit class Discard[T](val parse0: P[T]) extends AnyVal {
    def unary_- : P[Unit] = {
      parse0.map(_ => ())
    }
  }

  def pProgram[_: P]: P[Expr[jBoolean]] = P(-Start ~ -pSpace0 ~/ pOrQuery ~ -pSpace0 ~ -End).map(_.simplify())

  private def pOrQuery[_: P]: P[Expr[jBoolean]] = P(pAndQuery.rep(min=1, sep=pOr)).map(ands => new Or(ands.asJava))

  private def pAndQuery[_: P]: P[Expr[jBoolean]] = P(pNotQuery.rep(min=1, sep=pAnd)).map(nots => new And(nots.asJava))

  private def pNotQuery[_: P]: P[Expr[jBoolean]] = P((-pNot ~ pSubQuery).map(new Not(_)) | pSubQuery)

  private def pSubQuery[_: P]: P[Expr[jBoolean]] = P(
    (-CharIn("(") ~/ -pSpace0 ~/ pOrQuery ~/ -pSpace0 ~/ -CharIn(")"))
    | pNestedQuery
  )

  private def pNestedQuery[_: P]: P[Expr[jBoolean]] = P(
    (pField ~ -pSpace0 ~ -CharIn(":") ~ -pSpace0 ~ -CharIn("{") ~/ -pSpace0 ~ pOrQuery ~ -pSpace0 ~ -CharIn("}")).flatMap {
      case (field: StringLiteral, query) => Pass(new NestedPredicate(field.getValue, query))
      case _ => Fail
    }
    | pExpression
  )

  private def pExpression[_: P] = P(pFieldRangeExpression | pFieldValueExpression | pValueExpression.map {
    case w: StringLiteral if w.getValue == "*" =>
      new FieldMatchPred(DefaultFields.INSTANCE, AnyValue.INSTANCE) // TODO treat prefix+wildcard specially?
    case lit =>
      new FieldMatchPred(DefaultFields.INSTANCE, new SingleValue(lit))
  })

  private def pField[_: P] = P(pLiteral).flatMap {
    case lit: StringLiteral => Pass(lit)
    case _ => Fail
  }

  private def pFieldRangeExpression[_: P]: P[ComparisonOp[_]] = P(pField ~ -pSpace0 ~ -CharIn(":").? ~ pRangeOperator.! ~/ -pSpace0 ~ pLiteral).flatMapX {
    case (field: StringLiteral, opS, lit) =>
      opS match {
        case ">=" => Pass(new GreaterEqual(new GetField(field.getValue, ValueType.ANY), lit))
        case "<=" => Pass(new LessEqual(new GetField(field.getValue, ValueType.ANY), lit))
        case ">" => Pass(new Greater(new GetField(field.getValue, ValueType.ANY), lit))
        case "<" => Pass(new Less(new GetField(field.getValue, ValueType.ANY), lit))
        case _ => Fail
      }
    case _ => Fail
  }

  private def pRangeOperator[_: P] = P(("<=" | ">=" | "<" | ">").!)

  private def pFieldValueExpression[_: P] = P(pField ~ -pSpace0 ~ -CharIn(":") ~/ -pSpace0 ~ pListOfValues).map {
    case (field, listOfValues) =>
      val target = field match {
        case s: StringLiteral if s.getValue == "*" => DefaultFields.INSTANCE
        case s: StringLiteral if s.getValue.contains("*") => new FieldNameWildcard(s.getValue)
        case s: StringLiteral => new OneField(s.getValue)
      }

      val matchValue = listOfValues match {
        case sv: SingleValue =>
          sv.getValue match {
            case s: StringLiteral if s.getValue == "*" => AnyValue.INSTANCE
            case l: Literal[_] => new SingleValue(l)
          }
        case other => other
      }

      new FieldMatchPred(target, matchValue)
  }

  private def pValueExpression[_: P] = P(pValue)

  private def pListOfValues[_: P]: P[ListOfValues] = P(
    (-CharIn("(") ~/ -pSpace0 ~ pOrListOfValues ~ -pSpace0 ~ -CharIn(")"))
    | pBetweenListOfValues
    | pValue.map(new SingleValue(_))
  )

  private def pBetweenListOfValues[_ : P]: P[BetweenValues[_]] = P(
    pLS ~/ pValue ~ -pTo ~/ pValue ~ pRS
  ).map {
    case (lo, hi) => new BetweenValues[Any](lo.asInstanceOf[Expr[Any]], hi.asInstanceOf[Expr[Any]])
  }

  private def pLS[_ : P] = P(CharIn("\\[") ~ -pSpace0)
  private def pRS[_ : P] = P(-pSpace0 ~ CharIn("\\]"))

  private def pTo[_ : P] = P(
    -pSpace1 ~ IgnoreCase("TO").! ~ -pSpace1
  )

  private def pOrListOfValues[_: P]: P[ListOfValues] = P(
    pAndListOfValues.rep(1, pOr).map(kids =>
      new OrValues(kids.asJava)
    )
  )

  private def pAndListOfValues[_: P]: P[ListOfValues] = P(
    pNotListOfValues.rep(1, pAnd).map(kids =>
      new AndValues(kids.asJava)
    )
  )

  private def pNotListOfValues[_: P]: P[ListOfValues] = P(
    (-pNot ~ pListOfValues).map(new NotValues(_))
    | pListOfValues
  )

  private def pValue[_: P] = P(pQuotedString | pUnquotedLiteral)

  private def pUnquotedLiteral[_: P]: P[Literal[_]] = P(pUnquotedCharacter.rep.!)
    .map(_.trim())
    .map {
      case "true" => BooleanLiteral.getTrue
      case "false" => BooleanLiteral.getFalse
      case "null" => NullLiteral.INSTANCE
      case s if numR.matches(s) => new DoubleLiteral(s.toDouble)
      case s => new StringLiteral(s)
    }

  private def pQuotedString[_: P] = P(CharIn("\"") ~ pQuotedCharacter.rep.! ~ CharIn("\"")).map(chars =>
    new StringLiteral(chars)
  )

  private def pOr[_: P] = P(-pSpace1 ~ (IgnoreCase("or") | "||").! ~ -pSpace1)
  private def pAnd[_: P] = P(-pSpace1 ~ (IgnoreCase("and") | "&&").! ~ -pSpace1)
  private def pNot[_: P] = P(IgnoreCase("not").! ~ -pSpace1)

  def pLiteral[_: P] = P(pQuotedString | pUnquotedLiteral)

  private def pSpace[_: P] = P(CharIn(" \r\t\n").!)
  private def pSpace0[_: P] = P(pSpace.rep.map(_.mkString("")))
  private def pSpace1[_: P] = P(pSpace.rep(1).map(_.mkString("")))

  private def pQuotedCharacter[_: P] = P(pEscapedWhitespace.! | """\"""".! | CharPred(_ != '"').!)

  private def pUnquotedCharacter[_: P] = P(
      pEscapedWhitespace.!
    | pEscapedSpecialCharacter.!
    | pEscapedKeyword.!
    | pWildcard.!
    | pChar.!
  )

  private def pChar[_ : P] = P(
    !pSpecialCharacter ~ !pKeyword ~ AnyChar.!
  )

  private def pWildcard[_: P] = P(CharIn("*").!)

  private def pEscapedWhitespace[_: P] = P(("\\t" | "\\r" | "\\n").!)

  private def pEscapedSpecialCharacter[_: P] = P(-CharIn("\\\\") ~ pSpecialCharacter)

  private def pEscapedKeyword[_: P] = P((-CharIn("\\\\") ~ IgnoreCase("or").! | IgnoreCase("and").! | IgnoreCase("not")).!)

  private def pKeyword[_: P] = P(pOr | pAnd | pNot | pTo)

  private def pSpecialCharacter[_: P] = P(CharIn("""\():<>"*{}[]""").!)
}
