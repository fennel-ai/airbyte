#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#

from __future__ import annotations

from abc import ABC
from typing import ClassVar, List, Tuple

from ci_connector_ops.pipelines import consts
from ci_connector_ops.pipelines.actions import environments
from ci_connector_ops.pipelines.bases import Step, StepResult
from dagger import CacheVolume, Container, Directory, QueryError


class GradleTask(Step, ABC):
    """
    A step to run a Gradle task.

    Attributes:
        task_name (str): The Gradle task name to run.
        title (str): The step title.
    """

    DEFAULT_TASKS_TO_EXCLUDE = ["airbyteDocker"]
    BIND_TO_DOCKER_HOST = True
    gradle_task_name: ClassVar

    # These are the lines we remove from the connector gradle file to ignore specific tasks / plugins.
    LINES_TO_REMOVE_FROM_GRADLE_FILE = [
        # Do not build normalization with Gradle - we build normalization with Dagger in the BuildOrPullNormalization step.
        "project(':airbyte-integrations:bases:base-normalization').airbyteDocker.output",
    ]

    @property
    def connector_java_build_cache(self) -> CacheVolume:
        return self.context.dagger_client.cache_volume("connector_java_build_cache")

    @property
    def build_include(self) -> List[str]:
        """Retrieve the list of source code directory required to run a Java connector Gradle task.

        The list is different according to the connector type.

        Returns:
            List[str]: List of directories or files to be mounted to the container to run a Java connector Gradle task.
        """
        return [
            str(dependency_directory)
            for dependency_directory in self.context.connector.get_local_dependency_paths(with_test_dependencies=True)
        ]

    async def _get_patched_connector_dir(self) -> Directory:
        """Patch the build.gradle file of the connector under test by removing the lines declared in LINES_TO_REMOVE_FROM_GRADLE_FILE.

        Returns:
            Directory: The patched connector directory
        """

        gradle_file_content = await self.context.get_connector_dir(include=["build.gradle"]).file("build.gradle").contents()
        patched_file_content = ""
        for line in gradle_file_content.split("\n"):
            if not any(line_to_remove in line for line_to_remove in self.LINES_TO_REMOVE_FROM_GRADLE_FILE):
                patched_file_content += line + "\n"
        return self.context.get_connector_dir().with_new_file("build.gradle", patched_file_content)

    async def _get_patched_build_src_dir(self) -> Directory:
        """Patch some gradle plugins.

        Returns:
            Directory: The patched buildSrc directory
        """

        build_src_dir = self.context.get_repo_dir("buildSrc")
        cat_gradle_plugin_content = await build_src_dir.file("src/main/groovy/airbyte-connector-acceptance-test.gradle").contents()
        # When running integrationTest in Dagger we don't want to run connectorAcceptanceTest
        # connectorAcceptanceTest is run in the AcceptanceTest step
        cat_gradle_plugin_content = cat_gradle_plugin_content.replace(
            "project.integrationTest.dependsOn(project.connectorAcceptanceTest)", ""
        )
        return build_src_dir.with_new_file("src/main/groovy/airbyte-connector-acceptance-test.gradle", cat_gradle_plugin_content)

    def _get_gradle_command(self, extra_options: Tuple[str] = ("--no-daemon", "--scan", "--build-cache")) -> List:
        command = (
            ["./gradlew"]
            + list(extra_options)
            + [f":airbyte-integrations:connectors:{self.context.connector.technical_name}:{self.gradle_task_name}"]
        )
        for task in self.DEFAULT_TASKS_TO_EXCLUDE:
            command += ["-x", task]
        return command

    async def _run(self) -> StepResult:
        connector_under_test = (
            environments.with_gradle(self.context, self.build_include, bind_to_docker_host=self.BIND_TO_DOCKER_HOST)
            .with_mounted_directory(str(self.context.connector.code_directory), await self._get_patched_connector_dir())
            .with_mounted_directory("buildSrc", await self._get_patched_build_src_dir())
            # Disable the Ryuk container because it needs privileged docker access that does not work:
            .with_env_variable("TESTCONTAINERS_RYUK_DISABLED", "true")
            .with_(environments.mounted_connector_secrets(self.context, f"{self.context.connector.code_directory}/secrets"))
            .with_exec(self._get_gradle_command())
        )

        results = await self.get_step_result(connector_under_test)

        await self._export_gradle_dependency_cache(connector_under_test)
        return results

    async def _export_gradle_dependency_cache(self, gradle_container: Container) -> Container:
        """Export the Gradle writable dependency cache to the read-only dependency cache path.
        The read-only dependency cache is persisted thanks to mounted cache volumes in environments.with_gradle().
        You can read more about Shared readonly cache here: https://docs.gradle.org/current/userguide/dependency_resolution.html#sub:shared-readonly-cache
        Args:
            gradle_container (Container): The Gradle container.

        Returns:
            Container: The Gradle container, with the updated cache.
        """
        try:
            cache_dirs = await gradle_container.directory(consts.GRADLE_CACHE_PATH).entries()
        except QueryError:
            cache_dirs = []
        if "modules-2" in cache_dirs:
            with_cache = gradle_container.with_exec(
                [
                    "rsync",
                    "--archive",
                    "--quiet",
                    "--times",
                    "--exclude",
                    "*.lock",
                    "--exclude",
                    "gc.properties",
                    f"{consts.GRADLE_CACHE_PATH}/modules-2/",
                    f"{consts.GRADLE_READ_ONLY_DEPENDENCY_CACHE_PATH}/modules-2/",
                ]
            )
            return await with_cache
        return gradle_container
