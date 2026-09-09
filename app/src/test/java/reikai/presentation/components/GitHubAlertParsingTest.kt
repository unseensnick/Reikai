package reikai.presentation.components

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Pins the shape the GFM parser gives a `> [!WARNING]` callout, which is what
 * [reikai.presentation.components.MarkdownAlert] reads. Nothing in the app can see this drift: a
 * markdown library bump that renamed the title token or reordered the children would leave the
 * callout drawing an empty box, with no crash and no failing screen.
 */
@DisplayName("GitHub alert parsing")
class GitHubAlertParsingTest {

    private fun parse(markdown: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

    private fun alertIn(markdown: String): ASTNode =
        parse(markdown).children.first { it.type == GFMElementTypes.ALERT }

    @ParameterizedTest(name = "{0}")
    @EnumSource(GitHubAlertType::class)
    fun `each marker opens an alert of its own type`(type: GitHubAlertType) {
        val markdown = "> [!${type.name}]\n> Body text.\n"

        alertTypeOf(alertIn(markdown), markdown) shouldBe type
    }

    @Test
    fun `a lower-case marker names the same type`() {
        val markdown = "> [!warning]\n> Body text.\n"

        alertTypeOf(alertIn(markdown), markdown) shouldBe GitHubAlertType.WARNING
    }

    @Test
    fun `the body drops the quote marker, the title and the line tokens`() {
        val markdown = "> [!WARNING]\n> First paragraph.\n>\n> Second paragraph.\n"

        val body = alertBodyNodes(alertIn(markdown))

        body shouldHaveSize 2
        body.map { it.type } shouldBe listOf(MarkdownElementTypes.PARAGRAPH, MarkdownElementTypes.PARAGRAPH)
    }

    @Test
    fun `a list inside an alert stays one list`() {
        val markdown = "> [!NOTE]\n> - first item\n> - second item\n"

        val body = alertBodyNodes(alertIn(markdown))

        body.map { it.type } shouldBe listOf(MarkdownElementTypes.UNORDERED_LIST)
    }

    @Test
    fun `a plain quote stays a quote`() {
        val markdown = "> Just a quote.\n"

        parse(markdown).children.first().type shouldBe MarkdownElementTypes.BLOCK_QUOTE
    }
}
