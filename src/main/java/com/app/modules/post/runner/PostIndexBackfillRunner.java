package com.app.modules.post.runner;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.app.modules.post.config.PostProperties;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.post.search.PostDocument;
import com.app.modules.post.search.PostDocumentFactory;
import com.app.modules.post.search.PostIndexMappingUpdater;
import com.app.modules.post.search.PostSearchRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Rewrites every published post document from PostgreSQL so it carries the publication time, the
 * place and the author list.
 *
 * <p>Runs by itself once at startup when the live mapping lacks {@code published_at}, which is how
 * an index built by an earlier release is recognised, and on demand with {@code
 * app.post.index.backfill}. The mapping is extended first, so the new fields exist before a
 * document writes them, and search keeps answering throughout: a document the backfill has not
 * reached yet simply has no value for the new fields. Batches walk the {@code (published_at, id)}
 * tuple. Failures are non-fatal because Elasticsearch is a rebuildable tier, and the next startup
 * runs the backfill again.
 */
@Slf4j
@Component
@Order(2)
public class PostIndexBackfillRunner implements ApplicationRunner {

    private static final int BATCH_SIZE = 100;

    private final PostRepository postRepository;
    private final PostSearchRepository postSearchRepository;
    private final PostIndexMappingUpdater mappingUpdater;
    private final PostDocumentFactory postDocumentFactory;
    private final PostProperties properties;

    public PostIndexBackfillRunner(
            PostRepository postRepository,
            PostSearchRepository postSearchRepository,
            PostIndexMappingUpdater mappingUpdater,
            PostDocumentFactory postDocumentFactory,
            PostProperties properties) {
        this.postRepository = postRepository;
        this.postSearchRepository = postSearchRepository;
        this.mappingUpdater = mappingUpdater;
        this.postDocumentFactory = postDocumentFactory;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean outdated = mappingUpdater.isMappingOutdated();
            if (!outdated && !properties.getIndex().isBackfill()) {
                return;
            }
            mappingUpdater.applyMapping();
            long rewritten = backfill();
            log.info("Posts index backfill rewrote {} documents", rewritten);
        } catch (Exception e) {
            // Non-fatal: never block startup on the rebuildable search tier.
            log.warn("Posts index backfill skipped due to Elasticsearch error: {}", e.getMessage());
        }
    }

    /**
     * Rewrites every published document.
     *
     * @return how many documents were written
     */
    public long backfill() {
        long rewritten = 0;
        Post cursor = null;
        while (true) {
            PageRequest page = PageRequest.of(0, BATCH_SIZE);
            List<Post> batch =
                    cursor == null
                            ? postRepository.findFirstPublished(PostStatus.PUBLISHED, page)
                            : postRepository.findPublishedBefore(
                                    PostStatus.PUBLISHED,
                                    cursor.getPublishedAt(),
                                    cursor.getId(),
                                    page);
            if (batch.isEmpty()) {
                break;
            }
            List<PostDocument> documents = postDocumentFactory.buildAll(batch);
            postSearchRepository.saveAll(documents);
            rewritten += documents.size();
            cursor = batch.get(batch.size() - 1);
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return rewritten;
    }
}
