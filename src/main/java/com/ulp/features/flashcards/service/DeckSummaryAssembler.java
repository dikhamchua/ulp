package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public DeckSummaryAssembler(FlashcardRepository cardRepository,
                                ClassRepository classRepository,
                                UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
    }

    /** Maps decks to summaries; {@code owner} is true when a deck's owner is the caller. */
    public List<DeckSummary> toSummaries(List<FlashcardDeck> decks, Long callerId) {
        if (decks.isEmpty()) return List.of();
        Map<Long, Long> counts = cardCounts(decks);
        Map<Long, String> ownerNames = ownerNames(decks);
        Map<Long, String> classNames = classNames(decks);
        List<DeckSummary> out = new ArrayList<>(decks.size());
        for (FlashcardDeck d : decks) {
            out.add(new DeckSummary(d.getId(), d.getTitle(), d.getDescription(),
                    counts.getOrDefault(d.getId(), 0L), d.isShared(),
                    d.getOwnerId().equals(callerId), ownerNames.get(d.getOwnerId()),
                    d.getClassId() == null ? null : classNames.get(d.getClassId())));
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

    private Map<Long, String> classNames(List<FlashcardDeck> decks) {
        Collection<Long> classIds = decks.stream().map(FlashcardDeck::getClassId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> map = new HashMap<>();
        if (classIds.isEmpty()) return map;
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }
}
