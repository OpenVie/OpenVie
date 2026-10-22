// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Java-side scorer for the approval sensitivity contract (issue #8).
 *
 * <p>The keyword vocabulary and the canonical fixtures are NOT in Java code:
 * they live in {@code contracts/approval/v1/sensitive-policy.json}, consumed
 * simultaneously by this scorer (runtime copy at
 * {@code approval/sensitive-policy-v1.json}, kept byte-identical by
 * {@code SensitivityPolicyContractSyncTest}) and by the Python
 * {@code sensitive_policy} module (its own contract test). {@code
 * policy_version} flows into process variables and the audit log so every
 * level decision is attributable to exactly one policy revision
 * (sensitive-keyword-v1).
 */
@Component
@Slf4j(topic = "WORKFLOW")
public class SensitivityPolicyService {

    private static final String RESOURCE = "approval/sensitive-policy-v1.json";

    private final ObjectMapper objectMapper;

    @Getter
    private String policyVersion;

    @Getter
    private List<String> terms;

    private Pattern matcher;

    public SensitivityPolicyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Loads the classpath policy; public so the contract-sync tests hit the same path. */
    @PostConstruct
    public void loadPolicy() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(in.readAllBytes());
            policyVersion = root.path("policy_version").asText();
            List<String> words = new ArrayList<>();
            root.path("terms").forEach(term -> words.add(term.asText()));
            terms = List.copyOf(words);
            matcher = compile(terms);
            log.info("Sensitivity policy {} loaded with {} terms", policyVersion, terms.size());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read the sensitivity policy contract", e);
        }
    }

    /** Same lexical folding as sensitive_policy.is_sensitive_query. */
    public boolean isSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return matcher.matcher(fold(text)).find();
    }

    /**
     * The level decision of the process: sensitive score or a shared/public
     * publish target require the organization owner (level 2); everything
     * else is a plain workspace-admin approval (level 1).
     */
    public int decisionLevel(boolean sensitive, String publishTarget) {
        return sensitive || isSpecialTarget(publishTarget) ? 2 : 1;
    }

    public boolean isSpecialTarget(String publishTarget) {
        return "shared".equalsIgnoreCase(publishTarget) || "public".equalsIgnoreCase(publishTarget);
    }

    /** Python: casefold, NFKD, đ→d, drop combining marks. */
    private String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("đ", "d");
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!isCombiningMark(c)) {
                folded.append(c);
            }
        }
        return folded.toString();
    }

    private boolean isCombiningMark(char c) {
        int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /**
     * Mirrors the Python construction: whole-word alternatives like
     * {@code (?<!\w)(?:a|b\s+c)(?!\w)} on the folded text, so terms may span
     * multiple words ("interest rate", "lai suat").
     */
    private static Pattern compile(List<String> terms) {
        StringBuilder source = new StringBuilder("(?<![A-Za-z0-9_\\p{L}\\p{N}])(?:");
        List<String> parts = new ArrayList<>();
        for (String term : terms) {
            String[] pieces = term.split("\\s+");
            List<String> quoted = new ArrayList<>();
            for (String piece : pieces) {
                quoted.add(Pattern.quote(piece));
            }
            parts.add(String.join("\\s+", quoted));
        }
        source.append(String.join("|", parts)).append(")(?![A-Za-z0-9_\\p{L}\\p{N}])");
        return Pattern.compile(source.toString());
    }
}
