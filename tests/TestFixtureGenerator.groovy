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

    static void generateTestCorpus(File targetDir, boolean forceRecreate = false) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        File mainDb = new File(targetDir, 'memory.db')
        File auxDb = new File(targetDir, 'auxiliary.db')
        File datasetsFile = new File(targetDir, 'datasets.json')
        File fixturesDir = new File(targetDir, 'fixtures')
        fixturesDir.mkdirs()

        // 1. Generate memory.db
        boolean needMain = forceRecreate || !mainDb.exists() || mainDb.length() < 1000
        if (!needMain) {
            try {
                MemoryEngine eng = new MemoryEngine(mainDb.absolutePath)
                if ((eng.getStats().total_documents ?: 0) == 0) needMain = true
                eng.close()
            } catch (Exception e) {
                needMain = true
            }
        }
        if (needMain) {
            if (mainDb.exists()) mainDb.delete()
            generateMainTestDb(mainDb)
        }

        // 2. Generate auxiliary.db
        boolean needAux = forceRecreate || !auxDb.exists() || auxDb.length() < 1000
        if (!needAux) {
            try {
                MemoryEngine eng = new MemoryEngine(auxDb.absolutePath)
                if ((eng.getStats().total_documents ?: 0) == 0) needAux = true
                eng.close()
            } catch (Exception e) {
                needAux = true
            }
        }
        if (needAux) {
            if (auxDb.exists()) auxDb.delete()
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
                        databases: ["memory.db"]
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

        // 4. Generate sample ZIP archive and loose file
        File sampleZip = new File(fixturesDir, 'sample_archive.zip')
        if (!sampleZip.exists() || sampleZip.length() == 0) {
            generateSampleZip(sampleZip)
        }

        File sampleDoc = new File(fixturesDir, 'sample_document.txt')
        if (!sampleDoc.exists() || sampleDoc.length() == 0) {
            sampleDoc.text = "Sample loose document content for direct directory ingestion testing."
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
 * Provisioning of customer contracts and lifecycle management.
 */
public class BOregister {
    private BusinessPartner currentPartner;
    private Contract activeContract;

    public void registerPartner(BusinessPartner partner) {
        this.currentPartner = partner;
        System.out.println("Registering BusinessPartner: " + partner.getId());
    }

    public void provisionCustomer(String event) {
        if ("CUSTOMER.NEW".equals(event)) {
            System.out.println("Provisioning new customer contract.");
        }
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

/**
 * BusinessPartner domain entity representing customer accounts.
 */
public class BusinessPartner {
    private Long id;
    private String name;
    private String customerContractId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCustomerContractId() { return customerContractId; }
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
        <property name="provisioningMode" value="AUTO"/>
    </bean>
    <bean id="contractManager" class="com.lhs.Contract.service.ContractService"/>
</beans>
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/db/schema/V1_create_tables.sql',
                extension: '.sql',
                content: '''-- Database Schema Definition for BusinessPartner and Contracts
CREATE TABLE business_partner (
    partner_id INT PRIMARY KEY,
    partner_name VARCHAR(255) NOT NULL,
    provisioning_status VARCHAR(50),
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

The system manages BusinessPartner lifecycle, customer contract creation, and customer provisioning.
Key components:
- BOregister: Core registration endpoint
- application-context.xml: Spring bean configurations
- V1_create_tables.sql: Database schema
'''
            ],
            [
                source_archive: 'cms_R1.zip',
                file_path: 'BSCSr5(jerez1)/lhsj_main/bscs/cms/src/cms/java/com/lhs/BusinessPartner/Registry/PartnerRegistry.java',
                extension: '.java',
                content: '''package com.lhs.BusinessPartner.Registry;

public class PartnerRegistry {
    // Registry of all active BusinessPartner instances
    public void addBusinessPartner(String name) {}
}
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

        eng.beginBatch()
        Map<String, List<Map>> byArchive = docs.groupBy { it.source_archive }
        byArchive.each { String archName, List<Map> archDocs ->
            long totalBytes = 0
            archDocs.each { d ->
                String fPath = d.file_path.toString()
                String fName = new File(fPath).name
                String ext = d.extension.toString()
                String cnt = d.content.toString()
                long sBytes = cnt.getBytes("UTF-8").length
                totalBytes += sBytes
                eng.insertDocument(archName, fPath, fName, ext, sBytes, cnt)
            }
            eng.recordManifest(archName, totalBytes * 2, archDocs.size(), totalBytes, "/fixtures/${archName}", "hash_${archName}", "content_hash_${archName}")
        }
        eng.endBatch()
        eng.optimizeIndex()
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

        eng.beginBatch()
        long totalBytes = 0
        docs.each { d ->
            String fPath = d.file_path.toString()
            String fName = new File(fPath).name
            String ext = d.extension.toString()
            String cnt = d.content.toString()
            long sBytes = cnt.getBytes("UTF-8").length
            totalBytes += sBytes
            eng.insertDocument('physics_core.zip', fPath, fName, ext, sBytes, cnt)
        }
        eng.recordManifest('physics_core.zip', totalBytes * 2, docs.size(), totalBytes, "/fixtures/physics_core.zip", "hash_physics", "content_hash_physics")
        eng.endBatch()
        eng.optimizeIndex()
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
