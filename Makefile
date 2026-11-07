SHELL := /bin/bash

APP_NAME      := k8s-spark-submitter
VERSION       := $(shell ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || echo "1.0.0")
SPARK_IMAGE   ?= docker.io/library/spark:4.0.1
SPARK_VERSION ?= 4.0.1
BUILD_NUMBER  ?= 1
DOCKER_REPO   ?= venkomirisetti/k8s-spark-submitter
IMAGE_TAG     ?= $(DOCKER_REPO):$(SPARK_VERSION)-$(BUILD_NUMBER)
# Maven profile for the Spark line: empty for Spark 4.0/4.1, spark-4.2 or spark-4.3 for Jetty 12 builds.
MAVEN_PROFILE ?=
MVN_ARGS      := $(if $(MAVEN_PROFILE),-P$(MAVEN_PROFILE),)
PLATFORMS     ?= linux/amd64,linux/arm64

# ------------------------------------------------------------------------------
# Build
# ------------------------------------------------------------------------------

.PHONY: build test package clean image image-only push push-multiarch run

build:
	./mvnw clean compile $(MVN_ARGS)

test:
	./mvnw test $(MVN_ARGS)

package:
	./mvnw clean package -DskipTests -Dmaven.test.skip=true $(MVN_ARGS)

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

# Builds and pushes one image for every platform in PLATFORMS. The JAR is platform independent,
# so no emulation is needed. Run `make package` with the same MAVEN_PROFILE first.
push-multiarch:
	docker buildx build --platform $(PLATFORMS) --build-arg SPARK_IMAGE=$(SPARK_IMAGE) \
		-t $(IMAGE_TAG) -t $(DOCKER_REPO):$(SPARK_VERSION)-latest --push .

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
	@echo "  push-multiarch  Push a multi-platform image (after make package)"
	@echo ""
	@echo "Run:"
	@echo "  run         Build and run locally"
	@echo ""
	@echo "Variables:"
	@echo "  SPARK_IMAGE      Base Spark image (default: $(SPARK_IMAGE))"
	@echo "  DOCKER_REPO      Docker Hub repo (default: $(DOCKER_REPO))"
	@echo "  SPARK_VERSION    Spark version in tag (default: $(SPARK_VERSION))"
	@echo "  BUILD_NUMBER     Build number in tag (default: $(BUILD_NUMBER))"
	@echo "  MAVEN_PROFILE    spark-4.2 or spark-4.3 for Spark 4.2+ (default: none, Spark 4.0.1)"
	@echo "  PLATFORMS        Platforms for push-multiarch (default: $(PLATFORMS))"
	@echo "  IMAGE_TAG        Full image tag (default: $(IMAGE_TAG))"
