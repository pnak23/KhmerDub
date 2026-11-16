package com.khmerdub.api.dialogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DialogueSegmentRepository extends JpaRepository<DialogueSegment, UUID> {

    List<DialogueSegment> findByRevisionIdOrderBySequenceNoAsc(UUID revisionId);

    List<DialogueSegment> findByRevisionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(UUID revisionId, int fromSeq,
                                                                                            Limit limit);

    long countByRevisionId(UUID revisionId);
}
