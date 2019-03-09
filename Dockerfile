FROM alpine:3.7
COPY . /app
RUN make /app
CMD /app/run-dockerized.sh
