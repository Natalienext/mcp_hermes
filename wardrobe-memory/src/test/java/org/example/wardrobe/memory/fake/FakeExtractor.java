package org.example.wardrobe.memory.fake;

import java.util.ArrayList;
import java.util.List;

import org.example.wardrobe.memory.application.ExtractionInput;
import org.example.wardrobe.memory.application.Extractor;
import org.example.wardrobe.memory.application.ExtractorDecision;

public class FakeExtractor implements Extractor {

    public final List<ExtractorDecision> decisions = new ArrayList<>();
    public final List<ExtractionInput> inputs = new ArrayList<>();
    public RuntimeException failure;

    @Override
    public List<ExtractorDecision> extract(ExtractionInput input) {
        inputs.add(input);
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(decisions);
    }
}
