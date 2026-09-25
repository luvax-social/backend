FROM postgres:18

# Some JDBC clients (e.g. DBeaver via pgjdbc) resolve the JVM's default zone to the legacy IANA
# backward-compatibility name "Asia/Saigon" instead of its canonical replacement
# "Asia/Ho_Chi_Minh". This image's tzdata package ships only canonical names, so a client sending
# "Asia/Saigon" as a startup TimeZone parameter is rejected with FATAL before the session opens.
# The symlink makes the legacy name resolve identically to the canonical one.
RUN ln -s /usr/share/zoneinfo/Asia/Ho_Chi_Minh /usr/share/zoneinfo/Asia/Saigon

# First-boot init scripts (skipped when the data volume is already initialized)
COPY init/ /docker-entrypoint-initdb.d/
