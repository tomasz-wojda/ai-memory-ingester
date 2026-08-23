package fixtures

import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import groovy.json.JsonOutput

/**
 * Generates hermetic, lightweight synthetic test databases, archives, and datasets.json
 * inside a designated test directory (e.g. test-data/) for sub-second test suite execution.
 */
class TestFixtureGenerator {

    static void generateTestCorpus(File targetDir) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        File mainDb = new File(targetDir, 'test_memory.db')
        File auxDb = new File(targetDir, 'auxiliary.db')
        File datasetsFile = new File(targetDir, 'datasets.json')
        File fixturesDir = new File(targetDir, 'fixtures')
        fixturesDir.mkdirs()

        // 1. Generate test_memory.db if not present
        if (!mainDb.exists() || mainDb.length() == 0) {
            generateMainTestDb(mainDb)
        }

        // 2. Generate auxiliary.db if not present
        if (!auxDb.exists() || auxDb.length() == 0) {
            generateAuxiliaryTestDb(auxDb)
        }

        // 3. Generate datasets.json if not present
        if (!datasetsFile.exists() || datasetsFile.length() == 0) {
            Map datasetsJson = [
                schema_version: 1,
                active_dataset: "default",
                datasets: [
                    "default": [
                        created_at: LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME),
                        description: "Hermetic default test dataset",
                        databases: ["test_memory.db"]
                    ],
                    "physics": [
                        created_at: LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME),
                        description: "Hermetic physics test dataset",
                        databases: ["auxiliary.db"]
                    ]
                ]
            ]
            datasetsFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(datasetsJson))
        }

        // 4. Generate sample ZIP archive
        File sampleZip = new File(fixturesDir, 'sample_archive.zip')
        if (!sampleZip.exists() || sampleZip.length() == 0) {
            generateSampleZip(sampleZip)
        }
    }

    private static void generateMainTestDb(File dbFile) {
        MemoryEngine eng = new MemoryEngine(dbFile.absolutePath)

        List<Map> docs = [
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/src/cms/java/com/lhs/BusinessPartner/bscs_core/BOregister.java',
                extension: '.java',
                content: '''package com.lhs.BusinessPartner.bscs_core;

import com.lhs.BusinessPartner.model.BusinessPartner;
import com.lhs.Contract.model.Contract;

/**
 * BusinessPartner and Contract registration handler.
 */
public class BOregister {
    private BusinessPartner currentPartner;
    private Contract activeContract;

    public void registerPartner(BusinessPartner partner) {
        this.currentPartner = partner;
        System.out.println("Registering BusinessPartner: " + partner.getId());
    }

    public Contract getContract() {
        return this.activeContract;
    }
}
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/src/cms/java/com/lhs/BusinessPartner/model/BusinessPartner.java',
                extension: '.java',
                content: '''package com.lhs.BusinessPartner.model;

public class BusinessPartner {
    private Long id;
    private String name;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/config/application-context.xml',
                extension: '.xml',
                content: '''<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans">
    <bean id="businessPartnerService" class="com.lhs.BusinessPartner.bscs_core.BOregister">
        <property name="timeout" value="3000"/>
    </bean>
    <bean id="contractManager" class="com.lhs.Contract.service.ContractService"/>
</beans>
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/db/schema/V1_create_tables.sql',
                extension: '.sql',
                content: '''-- Database Schema Definition
CREATE TABLE business_partner (
    partner_id INT PRIMARY KEY,
    partner_name VARCHAR(255) NOT NULL,
    created_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE contract (
    contract_id INT PRIMARY KEY,
    partner_id INT REFERENCES business_partner(partner_id),
    status VARCHAR(50)
);
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/docs/architecture/overview.md',
                extension: '.md',
                content: '''# BSCS Architecture Overview

The system manages BusinessPartner lifecycle and Contract billing.
Key components:
- BOregister: Core registration endpoint
- application-context.xml: Spring bean configurations
'''
            ],
            [
                source_archive: 'feynman_lectures.pdf',
                file_path: 'lectures/physics/Brown - Selected Papers of Richard Feynman - With Commentary [physics] (World, 2000).pdf',
                extension: '.pdf',
                content: '''Selected Papers of Richard Feynman with Commentary.
Section III: Quantum Electrodynamics and Path Integrals and Operator Calculus: QED and Other Applications.
Path integrals provide a formulation of quantum mechanics through summation over histories.
'''
            ],
            [
                source_archive: 'scripts_pkg.zip',
                file_path: 'bin/run_pipeline',
                extension: '',
                content: '''#!/usr/bin/env bash
# Script without file extension for testing --no-ext filtering
echo "Starting pipeline..."
'''
            ]
        ]

        eng.ingestBatch(docs)
        eng.close()
    }

    private static void generateAuxiliaryTestDb(File dbFile) {
        MemoryEngine eng = new MemoryEngine(dbFile.absolutePath)

        List<Map> docs = [
            [
                source_archive: 'physics_core.zip',
                file_path: 'quantum/feynman_path_integrals.txt',
                extension: '.txt',
                content: 'Feynman path integral formulation and quantum electrodynamics operator calculus.'
            ],
            [
                source_archive: 'physics_core.zip',
                file_path: 'mechanics/landau_lifshitz.txt',
                extension: '.txt',
                content: 'Course of Theoretical Physics: Classical Mechanics and Electrodynamics of Continuous Media.'
            ]
        ]

        eng.ingestBatch(docs)
        eng.close()
    }

    private static void generateSampleZip(File zipFile) {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))
        try {
            zos.putNextEntry(new ZipEntry("sample_doc.txt"))
            zos.write("Sample document content for live archive testing.".getBytes("UTF-8"))
            zos.closeEntry()

            zos.putNextEntry(new ZipEntry("src/App.java"))
            zos.write("public class App { public static void main(String[] args) {} }".getBytes("UTF-8"))
            zos.closeEntry()
        } finally {
            zos.close()
        }
    }
}
