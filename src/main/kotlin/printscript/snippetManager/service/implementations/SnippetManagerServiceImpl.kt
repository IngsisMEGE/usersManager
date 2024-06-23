package printscript.snippetManager.service.implementations

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import printscript.snippetManager.controller.payload.request.FilterDTO
import printscript.snippetManager.controller.payload.request.SnippetEditDTO
import printscript.snippetManager.controller.payload.request.SnippetInputDTO
import printscript.snippetManager.controller.payload.response.SnippetOutputDTO
import printscript.snippetManager.controller.payload.response.SnippetViewDTO
import printscript.snippetManager.entity.Snippet
import printscript.snippetManager.entity.SnippetStatus
import printscript.snippetManager.repository.FilterRepository
import printscript.snippetManager.repository.SnippetRepository
import printscript.snippetManager.repository.SnippetStatusRepository
import printscript.snippetManager.service.interfaces.AssetService
import printscript.snippetManager.service.interfaces.SnippetManagerService
import reactor.core.publisher.Mono

@Service
class SnippetManagerServiceImpl(
    val snippetRepository: SnippetRepository,
    val snippetStatusRepository: SnippetStatusRepository,
    val assetService: AssetService,
    val filterRepository: FilterRepository,
) :
    SnippetManagerService {
    override fun createSnippet(
        snippet: SnippetInputDTO,
        userData: Jwt,
    ): Mono<SnippetOutputDTO> {
        val savedSnippet =
            snippetRepository.save(
                Snippet(
                    name = snippet.name,
                    language = snippet.language,
                    author = userData.claims["email"].toString(),
                ),
            )

        val snippetStatusEnum =
            snippetStatusRepository.save(
                SnippetStatus(
                    userEmail = userData.claims["email"].toString(),
                    snippet = savedSnippet,
                    status = printscript.snippetManager.enums.SnippetStatusEnum.PENDING,
                ),
            )

        return assetService.saveSnippetInBucket(savedSnippet.id, snippet.code)
            .then(
                Mono.just(
                    SnippetOutputDTO(
                        id = savedSnippet.id,
                        name = savedSnippet.name,
                        language = savedSnippet.language,
                        code = snippet.code,
                        author = savedSnippet.author,
                    ),
                ),
            ).onErrorResume { error ->
                snippetStatusRepository.delete(snippetStatusEnum)
                snippetRepository.delete(savedSnippet)
                throw Error("Error al guardar el snippet: ${error.message}")
            }
    }

    override fun editSnippet(
        id: Long,
        editedCode: SnippetEditDTO,
        userData: Jwt,
    ): Mono<SnippetOutputDTO> {
        val snippet = snippetRepository.findById(id)
        if (snippet.isEmpty) throw Error("Snippet no encontrado")

        if (snippet.get().author != userData.claims["email"].toString()) throw Error("No tienes permisos para editar este snippet")

        val snippetStatus = snippetStatusRepository.findBySnippetIdAndUserEmail(id, userData.claims["email"].toString())
        if (snippetStatus.isEmpty) throw Error("No te han compartido este snippet")
        snippetStatus.get().status = printscript.snippetManager.enums.SnippetStatusEnum.PENDING

        snippetStatusRepository.save(snippetStatus.get())

        assetService.deleteSnippetFromBucket(id).block()

        return assetService.saveSnippetInBucket(id, editedCode.code)
            .then(
                Mono.just(
                    SnippetOutputDTO(
                        id = snippet.get().id,
                        name = snippet.get().name,
                        language = snippet.get().language,
                        code = editedCode.code,
                        author = snippet.get().author,
                    ),
                ),
            ).onErrorResume { error ->
                throw Error("Error al guardar el snippet: ${error.message}")
            }
    }

    override fun searchSnippetsByFilter(
        filter: FilterDTO,
        page: Int,
        size: Int,
        userData: Jwt,
    ): Page<SnippetViewDTO> {
        val pageAndSizeRequest: Pageable = PageRequest.of(page, size, Sort.by("id").descending())
        return filterRepository.filterSnippets(filter, pageAndSizeRequest, userData.claims["email"].toString())
    }
}
