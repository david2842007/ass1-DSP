package com.example;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalStructureFactory;
import edu.stanford.nlp.trees.PennTreebankLanguagePack;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.trees.TypedDependency;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class StanfordAnalysis {

    // Load a standard English PCFG model from the stanford-parser jar
    private static final LexicalizedParser PARSER =
            LexicalizedParser.loadModel("edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz");

    private static final TokenizerFactory<CoreLabel> TOKENIZER_FACTORY =
            PTBTokenizer.factory(new CoreLabelTokenFactory(), "");

    private static final TreebankLanguagePack TLP = new PennTreebankLanguagePack();
    private static final GrammaticalStructureFactory GSF =
            TLP.grammaticalStructureFactory();

    /**
     * Perform linguistic analysis on the given input file using the Stanford parser.
     *
     * analysisType is one of:
     *   - "POS"           – part-of-speech tagging
     *   - "CONSTITUENCY"  – constituency parsing
     *   - "DEPENDENCY"    – dependency parsing
     *
     * The function writes the analysis result into a temporary output file and
     * returns the Path of that file.
     *
     * The input is processed line-by-line so that long files do not overwhelm the parser.
     */
    public static Path performAnalysis(String analysisType, Path inputFile) throws IOException {
        if (analysisType == null) {
            throw new IllegalArgumentException("analysisType must not be null");
        }

        String type = analysisType.trim().toUpperCase();
        if (!type.equals("POS") && !type.equals("CONSTITUENCY") && !type.equals("DEPENDENCY")) {
            throw new IllegalArgumentException("Unsupported analysisType: " + analysisType);
        }

        Path outputFile = Files.createTempFile("ass1-analysis-", ".txt");

        try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {

            String line;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    // Preserve blank lines
                    writer.write(System.lineSeparator());
                    continue;
                }

                try {
                    // Tokenize and parse this line
                    List<CoreLabel> tokens = tokenize(trimmed);
                    Tree parseTree = PARSER.apply(tokens);

                    writer.write("=== LINE " + lineNumber + " ===");
                    writer.newLine();
                    writer.write(trimmed);
                    writer.newLine();

                    switch (type) {
                        case "POS":
                            writePosTags(parseTree, writer);
                            break;
                        case "CONSTITUENCY":
                            writeConstituencyParse(parseTree, writer);
                            break;
                        case "DEPENDENCY":
                            writeDependencyParse(parseTree, writer);
                            break;
                        default:
                            // Should never get here because of the earlier check
                            throw new IllegalStateException("Unexpected analysis type: " + type);
                    }

                    writer.newLine(); // separator between lines

                } catch (Exception e) {
                    // If the parser fails on a specific line, record the error and continue
                    writer.write("### ERROR parsing line " + lineNumber + ": " + e.getMessage());
                    writer.newLine();
                    writer.newLine();
                }
            }
        }

        return outputFile;
    }

    private static List<CoreLabel> tokenize(String sentence) {
        Tokenizer<CoreLabel> tokenizer =
                TOKENIZER_FACTORY.getTokenizer(new StringReader(sentence));
        return tokenizer.tokenize();
    }

    // POS tagging using the parse tree's tagged yield
    private static void writePosTags(Tree parseTree, BufferedWriter writer) throws IOException {
        List<TaggedWord> taggedWords = parseTree.taggedYield();
        for (TaggedWord tw : taggedWords) {
            writer.write(tw.word());
            writer.write('\t');
            writer.write(tw.tag());
            writer.newLine();
        }
    }

    // Constituency parsing: print the Penn Treebank bracketed tree
    private static void writeConstituencyParse(Tree parseTree, BufferedWriter writer) throws IOException {
        // Tree.pennPrint needs a PrintWriter
        PrintWriter pw = new PrintWriter(writer, true);
        parseTree.pennPrint(pw);
    }

    // Dependency parsing: print typed dependencies (CC-processed)
    private static void writeDependencyParse(Tree parseTree, BufferedWriter writer) throws IOException {
        GrammaticalStructure gs = GSF.newGrammaticalStructure(parseTree);
        Collection<TypedDependency> dependencies = gs.typedDependenciesCCprocessed();
        for (TypedDependency td : dependencies) {
            writer.write(td.toString());
            writer.newLine();
        }
    }
}
