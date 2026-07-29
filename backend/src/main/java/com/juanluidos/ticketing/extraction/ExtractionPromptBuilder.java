package com.juanluidos.ticketing.extraction;

import com.juanluidos.ticketing.domain.Store;
import org.springframework.stereotype.Component;

/**
 * Construye la instrucción de extracción a partir de las capacidades declaradas
 * del súper, no de una plantilla escrita a mano por súper.
 *
 * <p>Si mañana entra un cuarto supermercado, se siembra su fila en {@code store}
 * con sus banderas y el prompt sale solo.
 */
@Component
public class ExtractionPromptBuilder {

    public String build(Store store) {
        StringBuilder p = new StringBuilder();

        p.append("""
                Eres un extractor de tickets de compra. Devuelve SOLO el JSON del esquema, sin \
                explicaciones ni razonamiento.

                MÉTODO, en dos pasos y en este orden:
                1. Transcribe cada fila física del bloque de artículos, LITERAL, tal cual está \
                impresa, en el campo raw_row_text. No interpretes nada todavía.
                2. Lee los demás campos de la línea A PARTIR de esa misma cadena.

                raw_row_text tiene que llevar la FILA ENTERA, de un margen al otro: la \
                descripción Y la cantidad Y el precio unitario Y el importe Y la letra de IVA, \
                todo lo que esté impreso en esa misma línea del papel. NO vale poner solo la \
                descripción: si el importe no está dentro de la cadena, este paso no sirve para \
                nada. Ejemplo de lo que se espera: "1 CUBO FREGAR C/RUEDAS        3,70".

                Esto es obligatorio porque las columnas del ticket están separadas por mucho \
                espacio en blanco y el papel va arrugado: si lees la descripción y el importe por \
                separado, acabas emparejando una descripción con el precio de la fila de al lado. \
                Nunca combines una descripción con un importe que no esté en su misma fila física.

                REGLAS GENERALES:
                - Los números del JSON van SIEMPRE con punto decimal, sea cual sea el separador \
                que imprima el ticket. El campo decimal_separator solo declara lo que el ticket usa.
                - quantity, unit_price y line_total tienen que cumplir quantity * unit_price = \
                line_total. Si no te cuadra, has leído mal alguna fila: vuelve a mirarla.

                sold_by — decide en este orden y NO te saltes el primer punto:
                1. Por DEFECTO, sold_by = "unit". Es lo que le toca a la inmensa mayoría de las \
                líneas de cualquier ticket: cualquier cosa envasada, embotellada, enlatada, en \
                bandeja, en paquete o vendida por piezas contadas. Un ticket normal tiene casi \
                todas sus líneas en "unit".
                2. sold_by = "weight" SOLO si en esa misma línea está impreso un peso Y un precio \
                por kilo, del estilo "1,394 kg  3,05 €/kg".
                3. sold_by = "piece_variable" SOLO en un caso muy concreto y muy raro: piezas \
                sueltas de peso variable que se cobran una a una, donde el ticket imprime \
                únicamente el precio final, SIN peso y SIN precio por kilo, y donde por eso el \
                mismo producto aparece repetido en el ticket con importes distintos. El ejemplo \
                típico es el tubo de pota. Si no se cumple TODO eso, NO es "piece_variable".
                No marques "piece_variable" solo porque la cantidad sea 1: eso es "unit". Si te \
                salen muchas líneas con "piece_variable", te has equivocado.

                - Si un dato no aparece en el ticket, ponlo a null. No lo inventes ni lo \
                calcules. Para los campos de texto usa null, nunca la cadena vacía.
                - purchased_at va EXACTAMENTE como AAAA-MM-DDTHH:MM:SS, con la T en medio. \
                Si el ticket no imprime segundos, pon 00. Copia las CUATRO cifras del año tal \
                cual están impresas, sin cambiar ninguna: si el ticket pone 2026, es 2026.
                - store.nif es solo el número: "B-90379843", sin el "NIF:" ni el "CIF:" de delante.
                - currency es el código ISO de TRES LETRAS mayúsculas: "EUR". Nunca el símbolo \
                "€" ni el nombre de la moneda. La moneda la marca el país del comercio, NO el \
                idioma en que esté escrito el ticket: un comercio en España cobra en EUR aunque \
                el ticket esté en chino.
                - En tax_breakdown, rate va como FRACCIÓN, no como porcentaje: el 21% se escribe \
                0.21, el 10% se escribe 0.10 y el 4% se escribe 0.04.
                - tax_letter es UNA sola letra mayúscula, o null. Nunca una palabra ni un trozo de \
                la descripción.
                - NO extraigas números de tarjeta, códigos de autorización, ARC ni AID. No \
                pertenecen al esquema.

                """);

        p.append("FORMATO DE ESTE TICKET — ").append(store.getName());
        if (store.getTaxId() != null) {
            p.append(" (").append(store.getTaxId()).append(")");
        }
        p.append(":\n");

        p.append("- Separador decimal impreso: ")
                .append("\"").append(store.getDecimalSeparator()).append("\"")
                .append(". Recuerda: en el JSON, punto igualmente.\n");

        if (store.isUnitPriceOnlyWhenMultiple()) {
            p.append("""
                    - El precio unitario SOLO se imprime cuando la cantidad es mayor que 1. Por \
                    tanto: un número al principio de la descripción es la cantidad únicamente si \
                    esa fila trae precio unitario. Si no lo trae, ese número es parte del nombre \
                    del producto y la cantidad es 1. Ejemplo real: "12 HUEVOS GRANDES-L 3,20" es \
                    UNA docena de huevos a 3,20, no doce unidades.
                    """);
        } else {
            p.append("- El precio unitario se imprime en todas las líneas, con la cantidad al lado.\n");
        }

        if (store.isHasWeightSubline()) {
            p.append("""
                    - Los productos a peso traen una sub-línea bajo la descripción con el peso y \
                    el precio por kilo ("1,394 kg  3,05 €/kg"). Esa sub-línea pertenece al \
                    artículo de ARRIBA: no es una línea aparte. Rellena weight y unit_price con \
                    ella y sold_by = "weight".
                    """);
        }

        if (store.isHasLineTaxLetter()) {
            p.append("""
                    - Cada línea acaba con una letra de IVA (A, B o C) a la derecha del importe. \
                    Cópiala en tax_letter. Va en la misma fila física que el importe.
                    """);
        } else {
            p.append("- No hay letra de IVA por línea: tax_letter va a null en todas.\n");
        }

        if (store.isHasArticleCount()) {
            p.append("- El ticket imprime un recuento de artículos (\"6 ART.\"): ponlo en article_count.\n");
        } else {
            p.append("- El ticket no imprime recuento de artículos: article_count va a null.\n");
        }

        if (store.isHasTaxBreakdown()) {
            p.append("- Copia el desglose de IVA (tipo, base y cuota) en totals.tax_breakdown.\n");
        }

        if (store.getNotes() != null && !store.getNotes().isBlank()) {
            p.append("- Notas del formato: ").append(store.getNotes()).append("\n");
        }

        return p.toString();
    }
}
