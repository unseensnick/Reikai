package yokai.domain.library.update.error.interactor

import yokai.domain.library.update.error.LibraryUpdateErrorRepository

class GetLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    fun subscribeAll() = repository.subscribeAll()
    fun countAsFlow() = repository.countAsFlow()
}
