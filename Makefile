IMAGE    := kv-node
PORT     := 7001
JMX_PORT := 9010

# Single-node dev build / run
build:
	docker build -f node/Dockerfile -t $(IMAGE) .

run: build
	docker run -d --rm \
		--cpus="1.0" \
		--memory="1g" \
		-p $(PORT):$(PORT) \
		-p $(JMX_PORT):$(JMX_PORT) \
		$(IMAGE)

stop:
	docker stop $$(docker ps -q --filter ancestor=$(IMAGE))

# Multi-node stack
up:
	docker compose up --build -d

down:
	docker compose down

.PHONY: build run stop up down
