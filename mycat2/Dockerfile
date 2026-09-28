# Mycat2 Docker Image (JDK 21)
FROM bellsoft/liberica-openjdk-debian:21

LABEL maintainer="mycat"

# Install dependencies
RUN apt-get update \
    && apt-get install -y procps \
    && rm -rf /var/lib/apt/lists/*

# Copy Mycat2 build output
COPY target/mycat2-*.tar.gz /opt/
RUN cd /opt && tar -xzf mycat2-*.tar.gz && rm mycat2-*.tar.gz \
    && chmod a+x /opt/mycat2/bin/*

ENV MYCAT_HOME /opt/mycat2
ENV PATH $MYCAT_HOME/bin:$PATH

VOLUME ["/opt/mycat2/conf", "/opt/mycat2/logs"]

EXPOSE 8066 1984

ENTRYPOINT ["mycat", "console"]
