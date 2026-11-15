FROM alpine:3.22
RUN apk add --no-cache iproute2 socat
COPY --chmod=755 entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh
EXPOSE 443
ENTRYPOINT ["/entrypoint.sh"]
