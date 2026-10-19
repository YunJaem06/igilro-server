package com.igilro.server.route.application

import com.igilro.server.route.domain.shade.BuildingShadeImport
import com.igilro.server.route.domain.shade.BuildingShadeImportRepository

class ImportBuildingShadeUseCase(private val repository: BuildingShadeImportRepository) {
    fun execute(dataset: BuildingShadeImport) = repository.save(dataset)
}
