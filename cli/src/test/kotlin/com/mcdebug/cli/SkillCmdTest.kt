package com.mcdebug.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SkillCmdTest {
    @Test
    fun `default dir is the agent-neutral skills dir under home`() {
        assertEquals(Path.of("/home/u/.agents/skills/mcdebug"), defaultSkillDir(Path.of("/home/u")))
    }
}
