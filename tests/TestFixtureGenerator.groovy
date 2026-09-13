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

    /**
     * Generates synthetic prompt-log fixtures for every supported dialect and recovery path.
     *
     * @param targetDir Fixture directory
     * @param forceRecreate Whether existing fixtures are replaced
     * @return Fixture files keyed by purpose
     */
    static Map<String, File> generateChatLogFixtures(File targetDir, boolean forceRecreate = false) {
        targetDir.mkdirs()
        LinkedHashMap<String, String> contents = [
            'fenced.log': '''--- PROMPT LOG ENTRY ---
TIMESTAMP: 2026-08-11 12:30:00 +0200
USER: Investigate CWP-11649 deployment state
ASSISTANT: Verified the deployment state.
--- END PROMPT LOG ENTRY ---
''',
            'labelled.log': '''TIMESTAMP: 2026-08-12 09:15 CEST
USER PROMPT: Plan KD-6607 remediation
ASSISTANT OUTPUT: Produced a remediation plan.
FINDINGS:
The service is recoverable.
================================================================================
''',
            'block.log': '''TIMESTAMP: 2026-08-13
MODE: EXECUTE
PROMPT: Apply KD-11 configuration
RESPONSE: Configuration applied.
FILES CHANGED:
config/settings.properties
================================================================================
''',
            'ticket.log': '''[2026-08-14 10:00:00] KD-6607 deployment analysis
--------------------------------------------------------------------------------
PROMPT: Inspect deployment
RESPONSE: Deployment is healthy.
================================================================================
''',
            'pair.log': '''[2026-08-15T08:00:00Z] USER: Check KD-7289 status
[2026-08-15T08:00:00Z] ASSISTANT: Status is ready.
''',
            'artifact.log': '''[2026-08-16 11:00:00] Infrastructure inventory
cluster=synthetic-cluster
endpoint=synthetic.internal
================================================================================
''',
            'boundary-matrix.log': '''=== 2026-08-22 08:00 ===
USER: Three-equals banner
ASSISTANT: Three-equals response
================================================================================
===== 2026-08-22 08:01 =====
USER: Five-equals banner
ASSISTANT: Five-equals response
================================================================================
--- 2026-08-22 08:02 ---
USER: Dash banner
ASSISTANT: Dash response
================================================================================
--- START PROMPT LOG ENTRY ---
TIMESTAMP: 2026-08-22 08:03
USER: Start fence
ASSISTANT: Start fence response
--- END PROMPT LOG ENTRY ---
--- PROMPT LOG ENTRY PARTIAL ---
DATE: 2026-08-22 08:04
USER: Partial fence
ASSISTANT: Partial fence response
--- END PROMPT LOG ENTRY ---
--- PROMPT LOG ENTRY 2026-08-22 08:05 ---
USER: Timestamp fence
ASSISTANT: Timestamp fence response
--- END PROMPT LOG ENTRY ---
[2026-08-22T08:06:00Z] PROMPT: Inline bracket prompt
[2026-08-22T08:06:00Z] ASSISTANT: Inline bracket response
[2026-08-22T08:07:00Z]
================================================================================
USER: Bare bracket header
ASSISTANT (RESEARCH): Qualified response
[2026-08-22T08:08:00Z] [MODE: PLAN]
================================================================================
USER (PLAN): Bracket mode header
RESPONSE (PLAN): Bracket mode response
[2026-08-22T08:09:00Z] KD-6607 ticket heading
--------------------------------------------------------------------------------
PROMPT: Bracket ticket header
RESPONSE: Bracket ticket response
[2026-08-22T08:10:00Z] Descriptive deployment heading
================================================================================
USER: Bracket descriptive header
ASSISTANT: Bracket descriptive response
2026-08-22 08:11 (continuation)
================================================================================
USER: Bare continuation header
ASSISTANT: Bare continuation response
2026-08-22 08:12 - MODE: EXECUTE
================================================================================
USER: Bare mode header
ASSISTANT: Bare mode response
2026-08-22 08:13 [MODE: RESEARCH] CWP-11649 ticket
================================================================================
USER: Bare bracket-mode ticket
ASSISTANT: Bare bracket-mode response
2026-08-22 08:14 - PROMPT INPUT
================================================================================
USER: Date prompt input
================================================================================
2026-08-22 08:14 - PROMPT OUTPUT
================================================================================
ASSISTANT: Date prompt output
TIMESTAMP: 2026-08-22 08:15
INPUT: Input alias
OUTPUT: Output alias response
''',
            'role-aliases.log': '''TIMESTAMP: 2026-08-23 08:00
USER QUERY: Query alias
ASSISTANT RESPONSE: Assistant response alias
USER CAUGHT ERROR: Error alias
RESPONSE SUMMARY: Response summary alias
USER PROMPT (MODE: PLAN): Qualified prompt alias
ASSISTANT (MODE: PLAN) - ACTIONS: Qualified assistant alias
PROMPT (EXECUTE): Qualified prompt
OUTPUT: Output response
''',
            'mixed-consecutive.log': '''USER: First consecutive user
ASSISTANT: First consecutive assistant
PROMPT: Second consecutive user
RESPONSE: Second consecutive assistant
INPUT: Third consecutive user
OUTPUT: Third consecutive assistant
''',
            'date-non-boundary.log': '''TIMESTAMP: 2026-08-24 08:00
USER: Preserve date-leading content
ASSISTANT: Review these lines:
2026-08-20-report.md
2026-08-21 deployment completed
2026-08-22T09:00:00Z service restarted
''',
            'unmatched-opener.log': '''--- PROMPT LOG ENTRY ---
TIMESTAMP: 2026-08-17 08:00
USER: Check unmatched opener
ASSISTANT: Recovered opener.
================================================================================
TIMESTAMP: 2026-08-17 09:00
USER: Check following entry
ASSISTANT: Following entry recovered.
''',
            'unmatched-closer.log': '''TIMESTAMP: 2026-08-18 08:00
USER: Check unmatched closer
ASSISTANT: Recovered closer.
--- END PROMPT LOG ENTRY ---
''',
            'oversized.log': """TIMESTAMP: 2026-08-19 08:00
USER: Preserve oversized KD-6999 result
ASSISTANT: ${'synthetic-artifact-body ' * 500}
================================================================================
""",
            'incremental.log': '''[2026-08-20T08:00:00Z] USER: Initial incremental turn
[2026-08-20T08:00:00Z] ASSISTANT: Initial incremental response.
''',
            'truncated.log': '''[2026-08-21T08:00:00Z] USER: Initial truncation turn
[2026-08-21T08:00:00Z] ASSISTANT: Initial truncation response.
'''
        ]
        Map<String, File> files = [:]
        contents.each { String name, String content ->
            File target = new File(targetDir, name)
            if (forceRecreate || !target.exists()) target.setText(content, 'UTF-8')
            files[name] = target
        }
        File duplicate = new File(targetDir, 'pair-duplicate.log')
        if (forceRecreate || !duplicate.exists()) duplicate.bytes = files['pair.log'].bytes
        files['pair-duplicate.log'] = duplicate
        [
            'source-prompt.fixture': 'Primary source pattern',
            'source-prompt-old.fixture': 'Old source pattern',
            'source-prompt-new.fixture': 'New source pattern',
            'source-pr.fixture': 'Pull request source pattern',
            'source-exporter.fixture': 'Exporter source pattern'
        ].eachWithIndex { String name, String prompt, int index ->
            File target = new File(targetDir, name)
            String content = """TIMESTAMP: 2026-08-25 08:0${index}
USER: ${prompt}
ASSISTANT: Source discovered.
"""
            if (forceRecreate || !target.exists()) target.setText(content, 'UTF-8')
            files[name] = target
        }
        return files
    }

    static Map<String, File> generateSequenceOrderFixtures(File targetDir, boolean forceRecreate = false) {
        File turns = new File(targetDir, 'sequence-turns')
        File artifacts = new File(targetDir, 'sequence-artifacts')
        File general = new File(targetDir, 'sequence-general')
        if (forceRecreate) {
            turns.deleteDir()
            artifacts.deleteDir()
            general.deleteDir()
        }
        [
            ['z-ticket/d20260913/execute/impact-none/turn-000013.md', 'sequence thirteen'],
            ['a-ticket/d20260911/plan/impact-none/turn-000011.md', 'sequence eleven'],
            ['m-ticket/d20260912/research/impact-none/turn-000012.md', 'sequence twelve']
        ].each { List<String> fixture ->
            File file = new File(turns, fixture[0])
            file.parentFile.mkdirs()
            file.setText(fixture[1], 'UTF-8')
        }
        File artifact = new File(artifacts, 'a-ticket/d20260914/artifact-000014.md')
        artifact.parentFile.mkdirs()
        artifact.setText('sequence artifact fourteen', 'UTF-8')
        general.mkdirs()
        new File(general, 'b.txt').setText('general beta', 'UTF-8')
        new File(general, 'a.txt').setText('general alpha', 'UTF-8')
        return [turns: turns, artifacts: artifacts, general: general]
    }

    static Map<String, File> generateWorklogFixtures(File targetDir, boolean forceRecreate = false) {
        File root = new File(targetDir, 'worklog')
        if (forceRecreate) root.deleteDir()
        root.mkdirs()
        String rule = '=' * 80
        File primary = new File(root, '2026-09-13_TEST-1234.log')
        primary.setText([
            rule,
            '  TICKET: TEST-1234',
            '  Summary: Synthetic authoritative memory',
            rule,
            '',
            rule,
            '  DECISIONS',
            rule,
            'RECOMMENDATION -> LOCKED: OPTION A',
            '',
            rule,
            '  RELATED TICKET',
            rule,
            'TEST-9999 is reference material only.',
            '',
            rule,
            '  BLOCKERS',
            rule,
            'ACTIVE: production approval is pending.',
            '',
            rule,
            '  STATUS',
            rule,
            '2026-09-10 12:00 - authoritative checkpoint.',
            'CURRENT: image 24-9 is verified.',
            'BLOCKER: promotion to master is required.',
            '',
            rule,
            '  NEXT ACTION',
            rule,
            '1. Promote develop to master.',
            '2. Deploy production.',
            ''
        ].join('\n'), 'UTF-8')
        File evidence = new File(root, '2026-09-13_TEST-1234_jira.log')
        evidence.setText('JIRA TICKET: TEST-1234\nRaw evidence payload.\n', 'UTF-8')
        File stale = new File(root, '2026-09-12_TEST-5678.log')
        stale.setText([
            rule, '  TICKET: TEST-5678', '  Summary: Revision fixture', rule, '',
            rule, '  STATUS', rule, '2026-09-12 10:00 - CURRENT: old state.', ''
        ].join('\n'), 'UTF-8')
        File turns = new File(targetDir, 'worklog-chat/turns/test1234')
        File newestHigh = writeTurn(
            new File(turns, 'd20260912/execute/impact-high/turn-000010.md'),
            '2026-09-12T12:00:00Z',
            'exact',
            'high',
            10,
            'TEST-1234 delivery advanced after the worklog.'
        )
        File newerLow = writeTurn(
            new File(turns, 'd20260913/research/impact-low/turn-000011.md'),
            '2026-09-13T12:00:00Z',
            'exact',
            'low',
            11,
            'TEST-1234 incidental low-impact observation.'
        )
        File olderHighSequence = writeTurn(
            new File(turns, 'd20260911/execute/impact-high/turn-009999.md'),
            '2026-09-11T12:00:00Z',
            'exact',
            'high',
            9999,
            'TEST-1234 older event with a larger sequence.'
        )
        File retrievalMeta = writeTurn(
            new File(turns, 'd20260915/research/impact-high/turn-010000.md'),
            '2026-09-15T12:00:00Z',
            'exact',
            'high',
            10000,
            'Compare top-20 snippets and stale deployment memory for TEST-1234.'
        )
        return [
            root: root,
            primary: primary,
            evidence: evidence,
            stale: stale,
            turns: turns,
            newestHigh: newestHigh,
            newerLow: newerLow,
            olderHighSequence: olderHighSequence,
            retrievalMeta: retrievalMeta
        ]
    }

    private static File writeTurn(File file, String timestamp, String confidence, String impact, long sequence, String body) {
        file.parentFile.mkdirs()
        file.setText([
            '---',
            "timestamp: \"${timestamp}\"",
            "timestamp_confidence: \"${confidence}\"",
            'mode: "execute"',
            'tickets: ["TEST-1234"]',
            'source: "prompt.log"',
            "seq: ${sequence}",
            "impact: \"${impact}\"",
            '---',
            '',
            '## User',
            '',
            body,
            ''
        ].join('\n'), 'UTF-8')
        return file
    }

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
