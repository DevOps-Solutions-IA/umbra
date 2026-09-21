FROM coturn/coturn@sha256:bbefd3e1fdfdc0d58770fe01b581fd8b00d9f3a5580d00acb77cf719a6bc78e3
USER root
# Upstream turnserver carries a file capability for privileged ports. A plain copy
# omits that xattr, allowing cap-drop ALL/no-new-privileges on our unprivileged ports.
RUN cp /usr/bin/turnserver /usr/local/bin/umbra-turnserver && chmod 0555 /usr/local/bin/umbra-turnserver
USER nobody:nogroup
ENTRYPOINT ["/usr/local/bin/umbra-turnserver"]
CMD ["-c", "/etc/coturn/turnserver.conf"]
