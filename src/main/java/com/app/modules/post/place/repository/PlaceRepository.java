package com.app.modules.post.place.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.post.place.entity.Place;

@Repository
public interface PlaceRepository extends JpaRepository<Place, UUID>, PlaceRepositoryCustom {

    Optional<Place> findByNormalizedName(String normalizedName);

    /**
     * Inserts a place unless one with the same normalised name already exists.
     *
     * <p>Never touches an existing row, so ordinary creation can never resurrect a banned or
     * deleted place by writing over an administrator's decision.
     *
     * @param name the label as typed
     * @param normalizedName the registry key
     * @param createdBy the account creating the place
     * @return 1 when the row was inserted, 0 when the name was already in the registry
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO places (name, normalized_name, created_by)"
                            + " VALUES (:name, :normalizedName, :createdBy)"
                            + " ON CONFLICT (normalized_name) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("name") String name,
            @Param("normalizedName") String normalizedName,
            @Param("createdBy") UUID createdBy);

    /**
     * Fuzzy place search using the pg_trgm similarity operator, ordered by popularity then name.
     *
     * <p>The PostgreSQL fallback behind the public search, narrowed the same way the search index
     * is: active places that at least one published post uses. A prefix match is added because a
     * trigram similarity over one or two characters matches almost nothing.
     *
     * @param query the normalised search term
     * @param limit maximum number of rows to return
     * @param offset number of leading rows to skip for pagination
     * @return matching places ordered by {@code post_count} descending, then {@code name}
     */
    @Query(
            value =
                    "SELECT * FROM places WHERE (normalized_name % :query"
                            + " OR normalized_name LIKE :query || '%')"
                            + " AND status = 'active' AND post_count > 0"
                            + " ORDER BY post_count DESC, name ASC LIMIT :limit OFFSET :offset",
            nativeQuery = true)
    List<Place> searchByNameTrgm(
            @Param("query") String query, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Loads places by id for the response assembler, regardless of status; the caller decides what
     * a banned or deleted place shows.
     *
     * @param ids place identifiers
     * @return the places that exist
     */
    List<Place> findAllByIdIn(Collection<UUID> ids);
}
