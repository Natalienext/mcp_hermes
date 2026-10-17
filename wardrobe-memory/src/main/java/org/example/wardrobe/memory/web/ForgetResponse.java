package org.example.wardrobe.memory.web;

import org.example.wardrobe.memory.domain.Fact;

public record ForgetResponse(String id, String status, String forgottenAt, String forgottenIn) {

    static ForgetResponse from(Fact fact) {
        return new ForgetResponse(fact.id(), fact.status().value(), fact.forgottenAt().toString(), fact.forgottenIn());
    }
}
