import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.example.StanfordAnalysis;

public class StanfordAnalysisTest {

    /**
     * Helper: create a temp input file with given lines.
     */
    private Path createTempInput(String... lines) throws IOException {
        Path inputFile = Files.createTempFile("ass1-input-", ".txt");
        Files.write(inputFile, java.util.Arrays.asList(lines), StandardCharsets.UTF_8);
        return inputFile;
    }

    /**
     * Helper: basic checks that any analysis produced some output.
     */
    private List<String> assertBasicOutput(Path outputFile) throws IOException {
        assertNotNull("Output path should not be null", outputFile);
        assertTrue("Output file should exist", Files.exists(outputFile));
        assertTrue("Output file should not be empty", Files.size(outputFile) > 0);

        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        assertFalse("Output should contain some lines", lines.isEmpty());
        return lines;
    }

    /**
     * POS test:
     * - runs analysis
     * - asserts there is at least one "word<TAB>tag" line
     */
    @Test
    public void testPerformAnalysisPos() throws Exception {
        Path inputFile = createTempInput("The quick brown fox jumps over the lazy dog.");

        Path outputFile = StanfordAnalysis.performAnalysis("POS", inputFile);

        List<String> lines = assertBasicOutput(outputFile);

        // POS lines should contain a tab: "word<TAB>tag"
        String firstPosLine = lines.stream()
                .filter(l -> l.contains("\t"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No POS lines (with a tab) found in output"));

        String[] parts = firstPosLine.split("\t");
        assertEquals("POS line should have 'word<TAB>tag' format", 2, parts.length);
        assertFalse("Word should not be empty", parts[0].trim().isEmpty());
        assertFalse("Tag should not be empty", parts[1].trim().isEmpty());
    }

    /**
     * POS test (case-insensitive analysis type).
     * The implementation uses toUpperCase(), so "pos" should also work.
     */
    @Test
    public void testPerformAnalysisPosCaseInsensitive() throws Exception {
        Path inputFile = createTempInput("Small test sentence.");

        Path outputFile = StanfordAnalysis.performAnalysis("pos", inputFile);

        List<String> lines = assertBasicOutput(outputFile);

        boolean hasPosLine = lines.stream().anyMatch(l -> l.contains("\t"));
        assertTrue("POS output should contain at least one 'word<TAB>tag' line", hasPosLine);
    }

    /**
     * Constituency test:
     * - checks that at least one line looks like a parse tree, starting with '('.
     */
    @Test
    public void testPerformAnalysisConstituency() throws Exception {
        Path inputFile = createTempInput("The quick brown fox jumps over the lazy dog.");

        Path outputFile = StanfordAnalysis.performAnalysis("CONSTITUENCY", inputFile);

        List<String> lines = assertBasicOutput(outputFile);

        // Tree lines usually start with '('
        boolean hasTreeLine = lines.stream()
                .anyMatch(l -> l.trim().startsWith("("));
        assertTrue("Constituency output should contain at least one tree line starting with '('", hasTreeLine);
    }

    /**
     * Dependency test:
     * - checks there is at least one typed-dependency-looking line:
     *   something like relation(head-idx, dep-idx)
     */
    @Test
    public void testPerformAnalysisDependency() throws Exception {
        Path inputFile = createTempInput("The quick brown fox jumps over the lazy dog.");

        Path outputFile = StanfordAnalysis.performAnalysis("DEPENDENCY", inputFile);

        List<String> lines = assertBasicOutput(outputFile);

        // Very loose pattern: relation(head-#, dep-#)
        boolean hasDependencyLine = lines.stream()
                .anyMatch(l -> l.matches("^[a-zA-Z_]+\\(.*-\\d+, .*?-\\d+\\).*"));
        assertTrue("Dependency output should contain at least one typed dependency line", hasDependencyLine);
    }

    /**
     * Test that empty / blank lines are handled gracefully and preserved as blank lines.
     * (Your implementation writes an empty line when it sees a blank line.)
     */
    @Test
    public void testPerformAnalysisHandlesEmptyLines() throws Exception {
        Path inputFile = createTempInput(
                "The quick brown fox jumps over the lazy dog.",
                "",
                "Another short sentence."
        );

        Path outputFile = StanfordAnalysis.performAnalysis("POS", inputFile);

        List<String> lines = assertBasicOutput(outputFile);

        // We expect at least one completely empty line (preserved blank line)
        boolean hasEmptyLine = lines.stream().anyMatch(l -> l.isEmpty());
        assertTrue("Output should contain at least one empty line for blank input line", hasEmptyLine);
    }

    /**
     * Invalid analysis type should throw IllegalArgumentException.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testPerformAnalysisInvalidType() throws Exception {
        Path inputFile = createTempInput("Some sentence.");
        // This should throw IllegalArgumentException
        StanfordAnalysis.performAnalysis("FOO", inputFile);
    }
}