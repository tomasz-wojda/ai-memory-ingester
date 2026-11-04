import groovy.json.JsonOutput

def entries = ChatLogParser.parseFile(new File(args[1]), 'prompt.log')
new File(args[0]).text = JsonOutput.toJson(entries)
