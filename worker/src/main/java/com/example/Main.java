package com.example;

import edu.stanford.nlp.parser.lexparser.LexicalizedParser;

public class Main {
    public static void main(String[] args) {
System.out.println("Worker starting...");

        // Tiny Stanford parser touch just to prove dependency works:
        LexicalizedParser lp = LexicalizedParser.loadModel();
        System.out.println("Stanford parser class loaded: " + lp.getClass().getName());    
    }
}