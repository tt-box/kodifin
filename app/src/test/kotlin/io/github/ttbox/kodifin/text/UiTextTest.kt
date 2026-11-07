package io.github.ttbox.kodifin.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    /** Ressources factices : « id(arg1,arg2) », pluriels « id#count(args) ». */
    private val res = object : TextResources {
        override fun string(id: Int, args: Array<Any>) = "$id(${args.joinToString(",")})"
        override fun plural(id: Int, count: Int, args: Array<Any>) = "$id#$count(${args.joinToString(",")})"
    }

    @Test
    fun `un texte brut est rendu tel quel`() {
        assertEquals("Salon", UiText.Raw("Salon").resolve(res))
    }

    @Test
    fun `une ressource transmet ses arguments`() {
        assertEquals("1(a,2)", UiText.Res(1, "a", 2).resolve(res))
        assertEquals(UiText.Res(1, listOf("a", 2)), UiText.Res(1, "a", 2))
    }

    @Test
    fun `un pluriel passe le nombre comme argument par défaut`() {
        assertEquals("2#9(9)", UiText.Plural(2, 9).resolve(res))
        assertEquals("2#1(x)", UiText.Plural(2, 1, listOf("x")).resolve(res))
    }

    @Test
    fun `les arguments UiText sont résolus avant insertion`() {
        val nested = UiText.Res(1, UiText.Raw("thor"), UiText.Plural(2, 3))
        assertEquals("1(thor,2#3(3))", nested.resolve(res))
    }
}
