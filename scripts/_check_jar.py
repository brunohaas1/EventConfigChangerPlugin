import zipfile, struct

z = zipfile.ZipFile('build/libs/EventConfigChanger.jar')
inner = 'com/eventchanger/quest/QuestConfig$QuestFlowConfig.class'
print('inner class present in jar:', inner in z.namelist())
if inner in z.namelist():
    d = z.read(inner)
    print('len', len(d))
    print('listItemX in inner class:', b'listItemX' in d)
    print('0.34  in inner class:', struct.pack('>d', 0.34) in d)
    print('0.151 in inner class:', struct.pack('>d', 0.151) in d)
    print('0.31  in inner class:', struct.pack('>d', 0.31) in d)
    print('0.586 in inner class:', struct.pack('>d', 0.586) in d)