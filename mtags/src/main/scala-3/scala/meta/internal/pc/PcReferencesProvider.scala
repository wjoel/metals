package scala.meta.internal.pc

import scala.collection.JavaConverters.*

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j
import org.eclipse.lsp4j.Location

class PcReferencesProvider(
    driver: InteractiveDriver,
    request: ReferencesRequest,
) extends WithCompilationUnit(driver, request.file()) with PcCollector[Option[(String, Option[lsp4j.Range])]]:
  override def allowZeroExtentImplicits: Boolean = true

  private def soughtSymbols =
    if(request.offsetOrSymbol().isLeft()) {
      val offsetParams = CompilerOffsetParams(
        request.file().uri(),
        request.file().text(),
        request.offsetOrSymbol().getLeft()
      )
      val symbolSearch = new WithCompilationUnit(driver, offsetParams) with PcSymbolSearch
      symbolSearch.soughtSymbols.map(_._1)
    } else {
      val semanticDBSymbols = request.alternativeSymbols().asScala.toSet + request.offsetOrSymbol().getRight()
      Some(semanticDBSymbols.flatMap(SymbolProvider.compilerSymbol).flatMap(symbolAlternatives(_))).filter(_.nonEmpty)
    }

  def collect(parent: Option[Tree])(
      tree: Tree | EndMarker,
      toAdjust: SourcePosition,
      symbol: Option[Symbol],
  ): Option[(String, Option[lsp4j.Range])] =
    val (pos, _) = toAdjust.adjust(text)
    tree match
      case t: DefTree if !request.includeDefinition() =>
        val sym = symbol.getOrElse(t.symbol)
        Some(SemanticdbSymbols.symbolName(sym), None)
      case t: Tree =>
        val sym = symbol.getOrElse(t.symbol)
        Some(SemanticdbSymbols.symbolName(sym), Some(pos.toLsp))
      case _ => None

  def references(): List[ReferencesResult] =
    soughtSymbols match
      case Some(sought) if sought.nonEmpty =>
        resultWithSought(sought)
        .flatten
        .groupMap(_._1) { case (_, optRange) =>
          optRange.map(new Location(request.file().uri().toString(), _))
        }
        .map { case (symbol, locs) =>
          PcReferencesResult(symbol, locs.flatten.asJava)
        }
        .toList
      case _ => Nil
end PcReferencesProvider
