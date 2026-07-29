package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.entity.FlashcardDeckClass;
import com.ulp.features.flashcards.repository.FlashcardDeckClassRepository;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batch-assembles {@link DeckSummary} rows from decks, resolving card counts,
 * owner names and class names in bulk (no N+1). Extracted from
 * {@code DeckService} to keep each class within the file-size budget.
 */
@Component
public class DeckSummaryAssembler {

    private final FlashcardRepository cardRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final FlashcardDeckClassRepository deckClassRepository;

    public DeckSummaryAssembler(FlashcardRepository cardRepository,
                                ClassRepository classRepository,
                                UserRepository userRepository,
                                FlashcardDeckClassRepository deckClassRepository) {
        this.cardRepository = cardRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
        this.deckClassRepository = deckClassRepository;
    }

    /** Maps decks to summaries; {@code owner} is true when a deck's owner is the caller. */
    public List<DeckSummary> toSummaries(List<FlashcardDeck> decks, Long callerId) {
        if (decks.isEmpty()) return List.of();
        Map<Long, Long> counts = cardCounts(decks);
        Map<Long, String> ownerNames = ownerNames(decks);
        Map<Long, List<String>> classNames = classNames(decks);
        List<DeckSummary> out = new ArrayList<>(decks.size());
        for (FlashcardDeck d : decks) {
            List<String> targets = classNames.getOrDefault(d.getId(), List.of());
            out.add(new DeckSummary(d.getId(), d.getTitle(), d.getDescription(),
                    counts.getOrDefault(d.getId(), 0L), !targets.isEmpty(),
                    d.getOwnerId().equals(callerId), ownerNames.get(d.getOwnerId()),
                    targets));
        }
        return out;
    }

    private Map<Long, Long> cardCounts(List<FlashcardDeck> decks) {
        List<Long> ids = decks.stream().map(FlashcardDeck::getId).toList();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : cardRepository.countByDeckIds(ids)) {
            map.put((Long) row[0], (Long) row[1]);
        }
        return map;
    }

    private Map<Long, String> ownerNames(List<FlashcardDeck> decks) {
        Collection<Long> ownerIds = decks.stream()
                .map(FlashcardDeck::getOwnerId).distinct().toList();
        Map<Long, String> map = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            map.put(u.getId(), u.getFullName());
        }
        return map;
    }

    /**
     * Target class names per deck, in exactly two queries no matter how many
     * decks are passed: one for all join rows, one for all referenced classes.
     * Decks with no targets are simply absent from the map.
     */
    private Map<Long, List<String>> classNames(List<FlashcardDeck> decks) {
        List<Long> deckIds = decks.stream().map(FlashcardDeck::getId).toList();
        List<FlashcardDeckClass> links = deckClassRepository.findByDeckIdIn(deckIds);
        if (links.isEmpty()) return Map.of();

        Set<Long> classIds = new LinkedHashSet<>();
        for (FlashcardDeckClass link : links) {
            classIds.add(link.getClassId());
        }
        Map<Long, String> nameById = new HashMap<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            nameById.put(c.getId(), c.getName());
        }

        Map<Long, List<String>> byDeck = new HashMap<>();
        for (FlashcardDeckClass link : links) {
            String name = nameById.get(link.getClassId());
            // A class row can be missing if it was hard-deleted out from under us.
            if (name == null) continue;
            byDeck.computeIfAbsent(link.getDeckId(), k -> new ArrayList<>()).add(name);
        }
        return byDeck;
    }
}
