import sys
sys.path.append('/home/vyacheslav/Desktop/utbot/UTBotJava/utbot-python/samples/samples')
import unittest
import builtins
import dicts
import types


class TestDictionary(unittest.TestCase):
    # region Test suites for executable dicts.translate
    # region
    def test_translate(self):
        dictionary = dicts.Dictionary([str(1.5 + 3.5j)], [])
        
        actual = dictionary.translate(str(id), str(1.5 + 3.5j))
        
        self.assertEqual(None, actual)
    
    def test_translate_throws_t(self):
        dictionary = dicts.Dictionary([], [{str(b'\x80'): str(b'\x80'), str(-1234567890): str(1e+300 * 1e+300), str(-123456789): str(b'\x80'), str('unicode remains unicode'): str()}, {str(b'\xf0\xa3\x91\x96', 'utf-8'): str(b'\x80'), str(-1234567890): str(), str(b'\x80'): str(1e+300 * 1e+300), str(1e+300 * 1e+300): str(b'\x80')}, {str(b'\x80'): str(1e+300 * 1e+300), str(id): str(b'\x80'), str(): str(), str(-123456789): str(1e+300 * 1e+300), str(1.5 + 3.5j): str()}, {str(b'\x80'): str(b'\x80'), str(-1234567890): str(1e+300 * 1e+300), str(-123456789): str(b'\x80'), str('unicode remains unicode'): str()}, {str(b'\x80'): str(1e+300 * 1e+300), str(id): str(b'\x80'), str(): str(), str(-123456789): str(1e+300 * 1e+300), str(1.5 + 3.5j): str()}])
        
        dictionary.translate(str('unicode remains unicode'), str(1.5 + 3.5j))
        
        # raises builtins.KeyError
    
    # endregion
    
    # endregion
    

