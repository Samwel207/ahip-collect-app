/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ahip

import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject

/** The score of one quiz attempt. */
data class AhipQuizScore(
  val points: Int,
  val total: Int,
  val right: Int,
  val count: Int,
  val timedOut: Boolean,
  val seconds: Int?,
) {
  val percent: Int
    get() = if (total > 0) Math.round(points * 100f / total) else 0
}

/**
 * Scores AHIP quizzes the same way as AHIP Manage (public/quiz.js): a form with urn:ahip:quiz, and
 * questions with urn:ahip:quiz-key {"correct": [...], "points": n}.
 */
object AhipQuiz {
  private const val QUIZ = "urn:ahip:quiz"
  private const val KEY = "urn:ahip:quiz-key"
  private const val SECONDS = "urn:ahip:quiz-seconds"
  private const val TIMED_OUT = "urn:ahip:quiz-timed-out"

  fun isQuiz(questionnaire: Questionnaire) = questionnaire.getExtensionByUrl(QUIZ) != null

  fun score(questionnaire: Questionnaire, response: QuestionnaireResponse): AhipQuizScore? {
    if (!isQuiz(questionnaire)) return null
    val answers = mutableMapOf<String, MutableList<String>>()
    fun collect(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
      items.forEach { item ->
        item.answer.forEach { answer ->
          val value = answer.value
          val text =
            when (value) {
              is Coding -> value.code
              is IntegerType -> value.value?.toString()
              is BooleanType -> value.value?.toString()
              else -> value?.primitiveValue()
            }
          if (text != null) answers.getOrPut(item.linkId) { mutableListOf() }.add(text)
          collect(answer.item)
        }
        collect(item.item)
      }
    }
    collect(response.item)

    var points = 0
    var total = 0
    var right = 0
    var count = 0
    fun walk(items: List<Questionnaire.QuestionnaireItemComponent>) {
      items.forEach { item ->
        val key =
          (item.getExtensionByUrl(KEY)?.value as? StringType)?.value?.let {
            runCatching { JSONObject(it) }.getOrNull()
          }
        val correct =
          key?.optJSONArray("correct")?.let { array ->
            (0 until array.length()).map { array.get(it).toString() }.filter { it.isNotBlank() }
          } ?: emptyList()
        if (correct.isNotEmpty()) {
          val worth = key!!.optInt("points", 1).coerceAtLeast(1)
          total += worth
          count++
          if (isCorrect(item, correct, answers[item.linkId].orEmpty())) {
            points += worth
            right++
          }
        }
        walk(item.item)
      }
    }
    walk(questionnaire.item)
    if (count == 0) return null
    return AhipQuizScore(
      points = points,
      total = total,
      right = right,
      count = count,
      timedOut = (response.getExtensionByUrl(TIMED_OUT)?.value as? BooleanType)?.booleanValue() == true,
      seconds = (response.getExtensionByUrl(SECONDS)?.value as? IntegerType)?.value,
    )
  }

  private fun normalise(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

  private fun isCorrect(
    item: Questionnaire.QuestionnaireItemComponent,
    correct: List<String>,
    given: List<String>,
  ): Boolean {
    val values = given.filter { it.isNotBlank() }
    if (values.isEmpty()) return false
    return when (item.type) {
      Questionnaire.QuestionnaireItemType.CHOICE,
      Questionnaire.QuestionnaireItemType.OPENCHOICE, -> values.toSet() == correct.toSet()
      Questionnaire.QuestionnaireItemType.INTEGER,
      Questionnaire.QuestionnaireItemType.DECIMAL, ->
        values.any { v -> correct.any { c -> v.toDoubleOrNull() != null && v.toDoubleOrNull() == c.toDoubleOrNull() } }
      else -> values.any { v -> correct.any { c -> normalise(c) == normalise(v) } }
    }
  }
}
