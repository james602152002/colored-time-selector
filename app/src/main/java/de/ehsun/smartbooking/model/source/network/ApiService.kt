package de.ehsun.smartbooking.model.source.network

import de.ehsun.smartbooking.entities.BookingInfo
import de.ehsun.smartbooking.entities.BookingResult
import de.ehsun.smartbooking.entities.Room
import de.ehsun.smartbooking.entities.RoomRequest
import io.reactivex.Flowable
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("https://roombooking.getsandbox.com/rooms")
    fun getRooms(@Body roomRequest: RoomRequest): Flowable<List<Room>>

    @POST("https://roombooking.getsandbox.com/sendpass")
    fun postBookingInfo(@Body bookingInfo: BookingInfo): Flowable<BookingResult>
}