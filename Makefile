SHELL := /bin/bash

APP_NAME      := k8s-spark-submitter
VERSION       := $(shell ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.0")
SPARK_IMAGE   ?= docker.io/library/spark:4.0.1
SPARK_VERSION ?= 4.0.1
BUILD_NUMBER  ?= 1
DOCKER_REPO   ?= venkomirisetti/k8s-spark-submitter
IMAGE_TAG     ?= $(DOCKER_REPO):$(SPARK_VERSION)-$(BUILD_NUMBER)

# ------------------------------------------------------------------------------
# Build
# ------------------------------------------------------------------------------

.PHONY: build test package clean image push run

build:
	./mvnw clean compile

test:
	./mvnw test

package:
	./mvnw clean package -DskipTests -Dmaven.test.skip=true

clean:
	./mvnw clean

# ------------------------------------------------------------------------------
# Docker
# ------------------------------------------------------------------------------

image: package
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) -t $(IMAGE_TAG) .

image-only:
	docker build --build-arg SPARK_IMAGE=$(SPARK_IMAGE) -t $(IMAGE_TAG) .

push: image
	docker tag $(IMAGE_TAG) $(DOCKER_REPO):$(SPARK_VERSION)-latest
	docker tag $(IMAGE_TAG) $(DOCKER_REPO):latest
	docker push $(IMAGE_TAG)
	docker push $(DOCKER_REPO):$(SPARK_VERSION)-latest
	docker push $(DOCKER_REPO):latest

# ------------------------------------------------------------------------------
# Run
# ------------------------------------------------------------------------------

run: package
	java -Dloader.path=/opt/spark/jars -jar target/$(APP_NAME)-$(VERSION).jar

# ------------------------------------------------------------------------------
# Help
# ------------------------------------------------------------------------------

.DEFAULT_GOAL := help

help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Build:"
	@echo "  build       Compile the project"
	@echo "  test        Run tests"
	@echo "  package     Build JAR (skip tests)"
	@echo "  clean       Clean build artifacts"
	@echo ""
	@echo "Docker:"
	@echo "  image       Build JAR and Docker image"
	@echo "  image-only  Build Docker image (assumes JAR exists)"
	@echo "  push        Build, tag, and push to Docker Hub"
	@echo ""
	@echo "Run:"
	@echo "  run         Build and run locally"
	@echo ""
	@echo "Variables:"
	@echo "  SPARK_IMAGE      Base Spark image (default: $(SPARK_IMAGE))"
	@echo "  DOCKER_REPO      Docker Hub repo (default: $(DOCKER_REPO))"
	@echo "  SPARK_VERSION    Spark version in tag (default: $(SPARK_VERSION))"
	@echo "  BUILD_NUMBER     Build number in tag (default: $(BUILD_NUMBER))"
	@echo "  IMAGE_TAG        Full image tag (default: $(IMAGE_TAG))"
