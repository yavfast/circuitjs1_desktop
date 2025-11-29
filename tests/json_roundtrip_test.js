/**
 * JSON Roundtrip Test Script
 * 
 * Тестує імпорт/експорт у форматі JSON на базових схемах.
 * 
 * Тест-план:
 * 1. Завантажити схему у text форматі
 * 2. Експорт у JSON
 * 3. Очистка схеми
 * 4. Імпорт з JSON
 * 5. Експорт у text
 * 6. Порівняння результатів
 * 
 * Використання через Chrome DevTools Console:
 * 1. Завантажте цей файл або скопіюйте код в консоль
 * 2. Викличте: await runJsonRoundtripTests()
 * 
 * Або через MCP evaluate_script:
 * mcp_chrome-devtoo_evaluate_script(function="async () => { ... }")
 */

// Базові схеми для тестування (шляхи відносно public/circuits/)
const TEST_CIRCUITS = [
    // Basics
    'ohms.txt',
    'resistors.txt',
    'cap.txt',
    'induct.txt',
    'voltdivide.txt',
    
    // Semiconductors
    'npn.txt',
    'pnp.txt',
    'diodevar.txt',
    
    // Oscillators
    'joule-thief.txt',
    'astable.txt',
    
    // OpAmps
    'amp-invert.txt',
    'amp-noninvert.txt',
    
    // Digital
    'and.txt',
    'nand.txt',
    'counter.txt',
    
    // Transformers
    'transformer.txt',
    
    // Filters
    'filt-lopass.txt',
    'filt-hipass.txt',
    
    // Power
    'fullrect.txt',
    'rectify.txt'
];

/**
 * Завантажує схему з файлу через fetch
 */
async function loadCircuitFile(filename) {
    const baseUrl = window.location.origin;
    const url = `${baseUrl}/circuits/${filename}`;
    
    try {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        return await response.text();
    } catch (e) {
        console.error(`Failed to load ${filename}:`, e);
        return null;
    }
}

/**
 * Нормалізує text формат для порівняння
 * - Видаляє зайві пробіли
 * - Сортує рядки для детермінованого порівняння
 * - Округляє числа з плаваючою точкою
 */
function normalizeTextFormat(text) {
    if (!text) return '';
    
    return text
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        // Не сортуємо, бо порядок важливий для $ рядка
        .map(line => {
            // Округляємо числа з великою точністю до 6 знаків
            return line.replace(/(-?\d+\.\d{7,})/g, (match) => {
                return parseFloat(match).toPrecision(6);
            });
        })
        .join('\n');
}

/**
 * Порівнює два text-формати і повертає детальний звіт
 */
function compareTextFormats(original, afterRoundtrip) {
    const origLines = original.split('\n').filter(l => l.trim());
    const newLines = afterRoundtrip.split('\n').filter(l => l.trim());
    
    const differences = [];
    
    // Порівняння кількості рядків
    if (origLines.length !== newLines.length) {
        differences.push({
            type: 'line_count',
            original: origLines.length,
            new: newLines.length
        });
    }
    
    // Порівняння рядків
    const maxLen = Math.max(origLines.length, newLines.length);
    for (let i = 0; i < maxLen; i++) {
        const origLine = origLines[i] || '';
        const newLine = newLines[i] || '';
        
        if (origLine !== newLine) {
            // Перевіряємо чи це просто різниця в точності чисел
            const origNorm = normalizeTextFormat(origLine);
            const newNorm = normalizeTextFormat(newLine);
            
            if (origNorm !== newNorm) {
                differences.push({
                    type: 'line_diff',
                    line: i + 1,
                    original: origLine,
                    new: newLine
                });
            }
        }
    }
    
    return {
        identical: differences.length === 0,
        differences: differences
    };
}

/**
 * Виконує тест roundtrip для однієї схеми
 */
async function testCircuitRoundtrip(circuitText, circuitName) {
    const result = {
        name: circuitName,
        success: false,
        steps: {},
        error: null
    };
    
    try {
        // Step 1: Імпорт з text формату
        CircuitJS1.importCircuit(circuitText, false);
        result.steps.import_text = true;
        
        // Даємо час на аналіз схеми
        await new Promise(resolve => setTimeout(resolve, 100));
        
        const elementCountAfterImport = CircuitJS1.getElementCount();
        result.steps.element_count_initial = elementCountAfterImport;
        
        // Step 2: Експорт у JSON
        const jsonExport = CircuitJS1.exportAsJson();
        result.steps.export_json = true;
        result.jsonSize = jsonExport.length;
        
        // Перевіряємо валідність JSON
        let jsonParsed;
        try {
            jsonParsed = JSON.parse(jsonExport);
            result.steps.json_valid = true;
        } catch (e) {
            result.steps.json_valid = false;
            result.error = `Invalid JSON: ${e.message}`;
            return result;
        }
        
        // Step 3: Очистка схеми
        CircuitJS1.clearCircuit();
        result.steps.clear = true;
        
        const elementCountAfterClear = CircuitJS1.getElementCount();
        if (elementCountAfterClear !== 0) {
            result.error = `Clear failed: ${elementCountAfterClear} elements remaining`;
            return result;
        }
        
        // Step 4: Імпорт з JSON
        CircuitJS1.importFromJson(jsonExport);
        result.steps.import_json = true;
        
        await new Promise(resolve => setTimeout(resolve, 100));
        
        const elementCountAfterJsonImport = CircuitJS1.getElementCount();
        result.steps.element_count_after_json = elementCountAfterJsonImport;
        
        // Перевірка кількості елементів
        if (elementCountAfterImport !== elementCountAfterJsonImport) {
            result.error = `Element count mismatch: ${elementCountAfterImport} vs ${elementCountAfterJsonImport}`;
            // Продовжуємо для отримання більше інформації
        }
        
        // Step 5: Експорт у text
        const textExportAfterRoundtrip = CircuitJS1.exportCircuit();
        result.steps.export_text = true;
        
        // Step 6: Порівняння результатів
        const comparison = compareTextFormats(circuitText, textExportAfterRoundtrip);
        result.comparison = comparison;
        result.steps.comparison = true;
        
        if (!comparison.identical && !result.error) {
            result.error = `Text format differences: ${comparison.differences.length} lines differ`;
        }
        
        result.success = comparison.identical && !result.error;
        
    } catch (e) {
        result.error = e.toString();
        result.stack = e.stack;
    }
    
    return result;
}

/**
 * Запускає тести для всіх базових схем
 */
async function runJsonRoundtripTests(circuitList = TEST_CIRCUITS) {
    console.log('=== JSON Roundtrip Tests ===');
    console.log(`Testing ${circuitList.length} circuits...`);
    console.log('');
    
    const results = {
        total: circuitList.length,
        passed: 0,
        failed: 0,
        skipped: 0,
        circuits: []
    };
    
    for (const filename of circuitList) {
        console.log(`Testing: ${filename}`);
        
        // Завантажуємо схему
        const circuitText = await loadCircuitFile(filename);
        
        if (!circuitText) {
            console.log(`  ⊘ SKIPPED: Could not load file`);
            results.skipped++;
            results.circuits.push({
                name: filename,
                status: 'skipped',
                error: 'Could not load file'
            });
            continue;
        }
        
        // Виконуємо тест
        const testResult = await testCircuitRoundtrip(circuitText, filename);
        results.circuits.push(testResult);
        
        if (testResult.success) {
            console.log(`  ✓ PASSED (${testResult.steps.element_count_initial} elements)`);
            results.passed++;
        } else {
            console.log(`  ✗ FAILED: ${testResult.error}`);
            if (testResult.comparison && testResult.comparison.differences.length > 0) {
                console.log(`    Differences:`);
                testResult.comparison.differences.slice(0, 3).forEach(diff => {
                    if (diff.type === 'line_count') {
                        console.log(`      Lines: ${diff.original} → ${diff.new}`);
                    } else {
                        console.log(`      Line ${diff.line}:`);
                        console.log(`        - ${diff.original.substring(0, 80)}`);
                        console.log(`        + ${diff.new.substring(0, 80)}`);
                    }
                });
            }
            results.failed++;
        }
    }
    
    // Підсумок
    console.log('');
    console.log('=== Summary ===');
    console.log(`Total:   ${results.total}`);
    console.log(`Passed:  ${results.passed}`);
    console.log(`Failed:  ${results.failed}`);
    console.log(`Skipped: ${results.skipped}`);
    console.log(`Success Rate: ${((results.passed / (results.total - results.skipped)) * 100).toFixed(1)}%`);
    
    return results;
}

/**
 * Тестує одну конкретну схему за ім'ям файлу
 */
async function testSingleCircuit(filename) {
    console.log(`Testing single circuit: ${filename}`);
    const circuitText = await loadCircuitFile(filename);
    
    if (!circuitText) {
        console.error('Could not load circuit file');
        return null;
    }
    
    const result = await testCircuitRoundtrip(circuitText, filename);
    
    console.log('Result:', result.success ? 'PASSED' : 'FAILED');
    if (!result.success) {
        console.log('Error:', result.error);
        console.log('Comparison:', result.comparison);
    }
    
    return result;
}

/**
 * Тестує roundtrip для поточної завантаженої схеми
 */
async function testCurrentCircuit() {
    console.log('Testing current circuit...');
    
    // Експортуємо поточну схему
    const originalText = CircuitJS1.exportCircuit();
    const elementCount = CircuitJS1.getElementCount();
    
    console.log(`Original: ${elementCount} elements, ${originalText.length} chars`);
    
    // Експорт в JSON
    const jsonExport = CircuitJS1.exportAsJson();
    console.log(`JSON: ${jsonExport.length} chars`);
    
    // Парсимо для перевірки
    const jsonData = JSON.parse(jsonExport);
    const jsonElementCount = Object.keys(jsonData.elements || {}).length;
    console.log(`JSON elements: ${jsonElementCount}`);
    
    // Очистка
    CircuitJS1.clearCircuit();
    
    // Імпорт з JSON
    CircuitJS1.importFromJson(jsonExport);
    
    await new Promise(resolve => setTimeout(resolve, 100));
    
    const newElementCount = CircuitJS1.getElementCount();
    console.log(`After JSON import: ${newElementCount} elements`);
    
    // Експорт назад в text
    const newText = CircuitJS1.exportCircuit();
    
    // Порівняння
    const comparison = compareTextFormats(originalText, newText);
    
    console.log('Identical:', comparison.identical);
    if (!comparison.identical) {
        console.log('Differences:', comparison.differences.length);
        comparison.differences.forEach(diff => {
            console.log(diff);
        });
    }
    
    return {
        success: comparison.identical && elementCount === newElementCount,
        originalElements: elementCount,
        newElements: newElementCount,
        comparison: comparison
    };
}

// Експортуємо функції в глобальний простір
window.runJsonRoundtripTests = runJsonRoundtripTests;
window.testSingleCircuit = testSingleCircuit;
window.testCurrentCircuit = testCurrentCircuit;

console.log('JSON Roundtrip Test Script loaded.');
console.log('Available functions:');
console.log('  - runJsonRoundtripTests() - test all basic circuits');
console.log('  - testSingleCircuit(filename) - test specific circuit');
console.log('  - testCurrentCircuit() - test currently loaded circuit');
