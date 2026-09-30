-- post_interaction_scores and user_similarity never had a writer or a reader. Refuse rather than
-- drop if either somehow holds rows, since that would mean an unknown writer exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM post_interaction_scores) OR EXISTS (SELECT 1 FROM user_similarity) THEN
        RAISE EXCEPTION 'post_interaction_scores or user_similarity holds rows; investigate before dropping';
    END IF;
END $$;
DROP TABLE post_interaction_scores;
DROP TABLE user_similarity;
