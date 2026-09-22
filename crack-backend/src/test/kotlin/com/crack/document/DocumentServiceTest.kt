package com.crack.document

import com.crack.document.service.DocumentService
import com.crack.document.service.DocumentService.DocumentType
import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

class DocumentServiceTest {

    private lateinit var documentService: DocumentService
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-doc-test")

        // 템플릿 생성
        val templateDir = tempDir.resolve("_templates")
        Files.createDirectories(templateDir)
        Files.writeString(templateDir.resolve("character.md"), "# 캐릭터: (이름)\n\n## 기본 정보\n")

        // 테스트용 시나리오 폴더 생성
        val scenarioDir = tempDir.resolve("테스트")
        Files.createDirectories(scenarioDir.resolve("characters"))
        Files.createDirectories(scenarioDir.resolve("memory"))
        Files.writeString(scenarioDir.resolve("world.md"), "# 세계관\n\n현대 서울")
        Files.writeString(scenarioDir.resolve("scenario.md"), "# 시나리오\n\n대학 캠퍼스")
        Files.writeString(scenarioDir.resolve("characters/protagonist.md"), "# 주인공\n\n김철수")

        documentService = DocumentService(DataPaths(DataPathConfig(dataPath = tempDir.toString())))
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    // --- 문서 읽기/쓰기 ---

    @Test
    fun `세계관 문서를 읽을 수 있다`() {
        val result = documentService.readDocument("테스트", DocumentType.WORLD)

        assertEquals("world", result.type)
        assertTrue(result.content.contains("현대 서울"))
    }

    @Test
    fun `시나리오 문서를 읽을 수 있다`() {
        val result = documentService.readDocument("테스트", DocumentType.SCENARIO)

        assertEquals("scenario", result.type)
        assertTrue(result.content.contains("대학 캠퍼스"))
    }

    @Test
    fun `주인공 문서를 읽을 수 있다`() {
        val result = documentService.readDocument("테스트", DocumentType.PROTAGONIST)

        assertEquals("protagonist", result.type)
        assertTrue(result.content.contains("김철수"))
    }

    @Test
    fun `세계관 문서를 수정할 수 있다`() {
        val newContent = "# 세계관\n\n판타지 왕국"
        val result = documentService.updateDocument("테스트", DocumentType.WORLD, newContent)

        assertEquals(newContent, result.content)

        // 파일에도 실제 반영 확인
        val fileContent = Files.readString(tempDir.resolve("테스트/world.md"))
        assertEquals(newContent, fileContent)
    }

    @Test
    fun `모든 문서를 목록으로 조회할 수 있다`() {
        val result = documentService.listDocuments("테스트")

        assertEquals(3, result.size)
        val types = result.map { it.type }.toSet()
        assertTrue(types.containsAll(setOf("world", "scenario", "protagonist")))
    }

    @Test
    fun `선택 문서 타입(prologue, keywords, commands, images)을 쓰고 읽을 수 있다`() {
        val types = mapOf(
            DocumentType.PROLOGUE to "prologue.md",
            DocumentType.KEYWORDS to "keywords.md",
            DocumentType.COMMANDS to "commands.md",
            DocumentType.IMAGES to "images.md",
        )
        for ((type, fileName) in types) {
            assertThrows<NotFoundException> { documentService.readDocument("테스트", type) }

            documentService.updateDocument("테스트", type, "# $fileName 내용")

            assertEquals("# $fileName 내용", Files.readString(tempDir.resolve("테스트").resolve(fileName)))
            val read = documentService.readDocument("테스트", type)
            assertEquals(type.name.lowercase(), read.type)
            assertEquals(fileName, read.name)
        }
        assertEquals(
            listOf("world", "scenario", "protagonist", "prologue", "keywords", "commands", "images"),
            documentService.listDocuments("테스트").map { it.type }
        )
    }

    @Test
    fun `존재하지 않는 시나리오 문서 조회 시 예외가 발생한다`() {
        assertThrows<NotFoundException> {
            documentService.readDocument("없는시나리오", DocumentType.WORLD)
        }
    }

    // --- 캐릭터 관리 ---

    @Test
    fun `캐릭터를 생성하면 템플릿이 적용된다`() {
        val result = documentService.createCharacter("테스트", "하은", null)

        assertEquals("character", result.type)
        assertEquals("하은", result.name)
        assertTrue(result.content.contains("# 캐릭터: 하은"), "템플릿의 (이름)이 하은으로 치환되어야 한다")

        // 파일 존재 확인
        assertTrue(Files.exists(tempDir.resolve("테스트/characters/하은.md")))
    }

    @Test
    fun `캐릭터를 커스텀 내용으로 생성할 수 있다`() {
        val customContent = "# 캐릭터: 준호\n\n활발한 남학생"
        val result = documentService.createCharacter("테스트", "준호", customContent)

        assertEquals("준호", result.name)
        assertEquals(customContent, result.content)
    }

    @Test
    fun `중복된 캐릭터 생성 시 예외가 발생한다`() {
        documentService.createCharacter("테스트", "하은", null)

        assertThrows<BadRequestException> {
            documentService.createCharacter("테스트", "하은", null)
        }
    }

    @Test
    fun `캐릭터 목록 조회 시 protagonist는 제외된다`() {
        documentService.createCharacter("테스트", "하은", null)
        documentService.createCharacter("테스트", "준호", null)

        val result = documentService.listCharacters("테스트")

        assertEquals(2, result.size)
        val names = result.map { it.name }.toSet()
        assertTrue(names.contains("하은"))
        assertTrue(names.contains("준호"))
        assertFalse(names.contains("protagonist"), "protagonist는 캐릭터 목록에 포함되면 안 된다")
    }

    @Test
    fun `캐릭터를 수정할 수 있다`() {
        documentService.createCharacter("테스트", "하은", "# 원본 내용")

        val updated = documentService.updateCharacter("테스트", "하은", "# 수정된 내용")

        assertEquals("# 수정된 내용", updated.content)
        assertEquals("# 수정된 내용", Files.readString(tempDir.resolve("테스트/characters/하은.md")))
    }

    @Test
    fun `캐릭터를 삭제할 수 있다`() {
        documentService.createCharacter("테스트", "하은", null)
        assertTrue(Files.exists(tempDir.resolve("테스트/characters/하은.md")))

        documentService.deleteCharacter("테스트", "하은")

        assertFalse(Files.exists(tempDir.resolve("테스트/characters/하은.md")))
    }

    @Test
    fun `존재하지 않는 캐릭터 조회 시 예외가 발생한다`() {
        assertThrows<NotFoundException> {
            documentService.readCharacter("테스트", "없는캐릭터")
        }
    }

    @Test
    fun `존재하지 않는 캐릭터 삭제 시 예외가 발생한다`() {
        assertThrows<NotFoundException> {
            documentService.deleteCharacter("테스트", "없는캐릭터")
        }
    }
}
